/**
 * Copyright (C) 2006 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject;

import com.google.common.base.Objects;
import static com.google.common.base.Preconditions.checkState;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.inject.internal.Annotations;
import com.google.inject.internal.BytecodeGen.Visibility;
import static com.google.inject.internal.BytecodeGen.newFastClass;
import com.google.inject.internal.Classes;
import com.google.inject.internal.CloseErrorsImpl;
import com.google.inject.internal.CloseableProvider;
import com.google.inject.internal.CompositeCloser;
import com.google.inject.internal.ConfigurationException;
import com.google.inject.internal.Errors;
import com.google.inject.internal.ErrorsException;
import com.google.inject.internal.FailableCache;
import com.google.inject.internal.MatcherAndConverter;
import com.google.inject.internal.ToStringBuilder;
import com.google.inject.matcher.Matcher;
import com.google.inject.spi.InjectionAnnotation;
import com.google.inject.spi.AnnotationProviderFactory;
import com.google.inject.spi.BindingTargetVisitor;
import com.google.inject.spi.CloseErrors;
import com.google.inject.spi.CloseFailedException;
import com.google.inject.spi.Closeable;
import com.google.inject.spi.Closer;
import com.google.inject.spi.Dependency;
import com.google.inject.spi.InjectionPoint;
import com.google.inject.util.Providers;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import net.sf.cglib.reflect.FastClass;
import net.sf.cglib.reflect.FastMethod;

/**
 * Default {@link Injector} implementation.
 *
 * @author crazybob@google.com (Bob Lee)
 * @see InjectorBuilder
 */
class InjectorImpl implements Injector {
  final Injector parentInjector;
  final Map<Key<?>, BindingImpl<?>> explicitBindings = Maps.newHashMap();
  final BindingsMultimap bindingsMultimap = new BindingsMultimap();
  final Map<Class<? extends Annotation>, Scope> scopes = Maps.newHashMap();
  final List<MatcherAndConverter> converters = Lists.newArrayList();
  final Map<Key<?>, BindingImpl<?>> parentBindings = Maps.newHashMap();
  final CreationTimeMemberInjector memberInjector = new CreationTimeMemberInjector(this);
  Reflection reflection;

  InjectorImpl(Injector parentInjector) {
    this.parentInjector = parentInjector;
  }

  /** Indexes bindings by type. */
  void index() {
    for (BindingImpl<?> binding : explicitBindings.values()) {
      index(binding);
    }
  }

  <T> void index(BindingImpl<T> binding) {
    bindingsMultimap.put(binding.getKey().getTypeLiteral(), binding);
  }

  // not test-covered
  public <T> List<Binding<T>> findBindingsByType(TypeLiteral<T> type) {
    return Collections.<Binding<T>>unmodifiableList(bindingsMultimap.getAll(type));
  }

  // not test-covered
  <T> List<String> getNamesOfBindingAnnotations(TypeLiteral<T> type) {
    List<String> names = Lists.newArrayList();
    for (Binding<T> binding : findBindingsByType(type)) {
      Key<T> key = binding.getKey();
      names.add(key.hasAnnotationType() ? key.getAnnotationName() : "[no annotation]");
    }
    return names;
  }

  /** Returns the binding for {@code key} */
  public <T> BindingImpl<T> getBinding(Key<T> key) {
    Errors errors = new Errors(key.getRawType());
    try {
      BindingImpl<T> result = getBindingOrThrow(key, errors);
      ProvisionException.throwNewIfNonEmpty(errors);
      return result;
    }
    catch (ErrorsException e) {
      throw new ProvisionException(errors.merge(e.getErrors()));
    }
  }

  /**
   * Gets a binding implementation.  First, it check to see if the parent has a binding.  If the
   * parent has a binding and the binding is scoped, it will use that binding.  Otherwise, this
   * checks for an explicit binding. If no explicit binding is found, it looks for a just-in-time
   * binding.
   */
  public <T> BindingImpl<T> getBindingOrThrow(Key<T> key, Errors errors)
      throws ErrorsException {
    if (parentInjector != null) {
      BindingImpl<T> bindingImpl = getParentBinding(key);
      if (bindingImpl != null) {
        return bindingImpl;
      }
    }

    // Check explicit bindings, i.e. bindings created by modules.
    BindingImpl<T> binding = getExplicitBindingImpl(key);
    if (binding != null) {
      return binding;
    }

    // Look for an on-demand binding.
    return getJustInTimeBinding(key, errors);
  }

  /**
   * Checks the parent injector for a scoped binding, and if available, creates an appropriate
   * binding local to this injector and remembers it.
   *
   * TODO: think about this wrt parent jit bindings
   */
  @SuppressWarnings("unchecked")
  private <T> BindingImpl<T> getParentBinding(Key<T> key) {
    synchronized (parentBindings) {
      // null values will mean that the parent doesn't have this binding
      BindingImpl<T> binding = (BindingImpl<T>) parentBindings.get(key);
      if (binding != null) {
        return (BindingImpl<T>) binding;
      }
      try {
        binding = (BindingImpl) parentInjector.getBinding(key);
      }
      catch (ProvisionException e) {
        // if this happens, the parent can't create this key, and we ignore it
      }

      BindingImpl<T> bindingImpl = null;
      if (binding != null
          && binding.getScope() != null
          && !binding.getScope().equals(Scopes.NO_SCOPE)) {
        // TODO: this binding won't report its injection points or scoping properly
        bindingImpl = new ProviderInstanceBindingImpl(
            this,
            key,
            binding.getSource(),
            new InternalFactoryToProviderAdapter(binding.getProvider(), binding.getSource()),
            Scopes.NO_SCOPE,
            binding.getProvider(),
            LoadStrategy.LAZY,
            ImmutableSet.<InjectionPoint>of());
      }
      parentBindings.put(key, bindingImpl); // this kinda scares me
      return bindingImpl;
    }
  }

  public <T> Binding<T> getBinding(Class<T> type) {
    return getBinding(Key.get(type));
  }

  /** Gets a binding which was specified explicitly in a module. */
  @SuppressWarnings("unchecked")
  <T> BindingImpl<T> getExplicitBindingImpl(Key<T> key) {
    return (BindingImpl<T>) explicitBindings.get(key);
  }

  /**
   * Returns a just-in-time binding for {@code key}, creating it if necessary.
   *
   * @throws com.google.inject.internal.ErrorsException if the binding could not be created.
   */
  @SuppressWarnings("unchecked")
  <T> BindingImpl<T> getJustInTimeBinding(Key<T> key, Errors errors) throws ErrorsException {
    synchronized (jitBindings) {
      // Support null values.
      if (!jitBindings.containsKey(key)) {
        BindingImpl<T> binding = createJustInTimeBinding(key, errors);
        jitBindings.put(key, binding);
        return binding;
      }
      else {
        return (BindingImpl<T>) jitBindings.get(key);
      }
    }
  }

  /** Just-in-time binding cache. */
  final Map<Key<?>, BindingImpl<?>> jitBindings = Maps.newHashMap();

  /* Returns true if the key type is Provider<?> (but not a subclass of Provider<?>). */
  static boolean isProvider(Key<?> key) {
    return key.getTypeLiteral().getRawType().equals(Provider.class);
  }

  /** Creates a synthetic binding to Provider<T>, i.e. a binding to the provider from Binding<T>. */
  private <T> BindingImpl<Provider<T>> createProviderBinding(Key<Provider<T>> key,
      LoadStrategy loadStrategy, Errors errors) throws ErrorsException {
    Type providerType = key.getTypeLiteral().getType();

    // If the Provider has no type parameter (raw Provider)...
    if (!(providerType instanceof ParameterizedType)) {
      throw errors.cannotInjectRawProvider().toException();
    }

    Type entryType = ((ParameterizedType) providerType).getActualTypeArguments()[0];

    @SuppressWarnings("unchecked") // safe because T came from Key<Provider<T>>
    Key<T> providedKey = (Key<T>) key.ofType(entryType);

    BindingImpl<T> delegate = getBindingOrThrow(providedKey, errors);
    return new ProviderBindingImpl<T>(this, key, delegate, loadStrategy);
  }

  static class ProviderBindingImpl<T> extends BindingImpl<Provider<T>> {

    final BindingImpl<T> providedBinding;

    ProviderBindingImpl(
        InjectorImpl injector,
        Key<Provider<T>> key,
        Binding<T> providedBinding,
        LoadStrategy loadStrategy) {
      super(
          injector,
          key,
          providedBinding.getSource(),
          createInternalFactory(providedBinding),
          Scopes.NO_SCOPE,
          loadStrategy);
      this.providedBinding = (BindingImpl<T>) providedBinding;
    }

    static <T> InternalFactory<Provider<T>> createInternalFactory(Binding<T> providedBinding) {
      final Provider<T> provider = providedBinding.getProvider();
      return new InternalFactory<Provider<T>>() {
        public Provider<T> get(Errors errors, InternalContext context, Dependency dependency) {
          return provider;
        }
      };
    }

    public <V> V acceptTargetVisitor(BindingTargetVisitor<? super Provider<T>, V> visitor) {
      return visitor.visitProviderBinding(providedBinding.getKey());
    }
  }

  /**
   * Converts a constant string binding to the required type.
   *
   * @return the binding if it could be resolved, or null if the binding doesn't exist
   * @throws com.google.inject.internal.ErrorsException if there was an error resolving the binding
   */
  private <T> BindingImpl<T> convertConstantStringBinding(Key<T> key, Errors errors)
      throws ErrorsException {
    // Find a constant string binding.
    Key<String> stringKey = key.ofType(String.class);
    BindingImpl<String> stringBinding = getExplicitBindingImpl(stringKey);
    if (stringBinding == null || !stringBinding.isConstant()) {
      return null;
    }

    String stringValue = stringBinding.getProvider().get();
    Object source = stringBinding.getSource();

    // Find a matching type converter.
    TypeLiteral<T> type = key.getTypeLiteral();
    MatcherAndConverter matchingConverter = null;
    for (MatcherAndConverter converter : converters) {
      if (converter.getTypeMatcher().matches(type)) {
        if (matchingConverter != null) {
          errors.ambiguousTypeConversion(stringValue, source, type, matchingConverter, converter);
        }
        matchingConverter = converter;
      }
    }

    if (matchingConverter == null) {
      // No converter can handle the given type.
      return null;
    }

    // Try to convert the string. A failed conversion results in an error.
    try {
      @SuppressWarnings("unchecked") // This cast is safe because we double check below.
      T converted = (T) matchingConverter.getTypeConverter().convert(stringValue, type);

      if (converted == null) {
        throw errors.converterReturnedNull(stringValue, source, type, matchingConverter)
            .toException();
      }

      if (!type.getRawType().isInstance(converted)) {
        throw errors.conversionTypeError(stringValue, source, type, matchingConverter, converted)
            .toException();
      }

      return new ConvertedConstantBindingImpl<T>(this, key, converted, stringBinding);
    }
    catch (ErrorsException e) {
      throw e;
    }
    catch (Exception e) {
      throw errors.conversionError(stringValue, source, type, matchingConverter, e)
          .toException();
    }
  }

  private static class ConvertedConstantBindingImpl<T> extends BindingImpl<T> {
    final T value;
    final Provider<T> provider;
    final Binding<String> originalBinding;

    ConvertedConstantBindingImpl(
        InjectorImpl injector, Key<T> key, T value, Binding<String> originalBinding) {
      super(injector, key, originalBinding.getSource(), new ConstantFactory<T>(value),
          Scopes.NO_SCOPE, LoadStrategy.LAZY);
      this.value = value;
      provider = Providers.of(value);
      this.originalBinding = originalBinding;
    }

    @Override public Provider<T> getProvider() {
      return provider;
    }

    public <V> V acceptTargetVisitor(BindingTargetVisitor<? super T, V> visitor) {
      return visitor.visitConvertedConstant(value);
    }

    @Override public String toString() {
      return new ToStringBuilder(Binding.class)
          .add("key", key)
          .add("value", value)
          .add("original", originalBinding)
          .toString();
    }
  }

  <T> BindingImpl<T> createBindingFromType(
      Key<T> key, LoadStrategy loadStrategy, Errors errors) throws ErrorsException {
    BindingImpl<T> binding = createUnitializedBinding(
        key, null, loadStrategy, errors);
    initializeBinding(binding, errors);
    return binding;
  }

  <T> void initializeBinding(BindingImpl<T> binding, Errors errors) throws ErrorsException {
    // Put the partially constructed binding in the map a little early. This enables us to handle
    // circular dependencies. Example: FooImpl -> BarImpl -> FooImpl.
    // Note: We don't need to synchronize on jitBindings during injector creation.
    if (binding instanceof ClassBindingImpl<?>) {
      Key<T> key = binding.getKey();
      jitBindings.put(key, binding);
      boolean successful = false;
      try {
        // TODO: does this put the binding in JIT bindings?
        binding.initialize(this, errors);
        successful = true;
      }
      finally {
        if (!successful) {
          jitBindings.remove(key);
        }
      }
    }
  }

  <T> BindingImpl<T> createUnitializedBinding(Key<T> key, Scope scope,
      LoadStrategy loadStrategy, Errors errors) throws ErrorsException {
    @SuppressWarnings("unchecked")
    Class<T> type = (Class<T>) key.getRawType();
    return createUnitializedBinding(key, type, scope, type, loadStrategy, errors);
  }

  /**
   * Creates a binding for an injectable type with the given scope. Looks for a scope on the type if
   * none is specified.
   *
   * TODO(jessewilson): Fix raw types! this method makes a binding for {@code Foo} from a request
   *     for {@code Foo<String>}
   *
   * @param type the raw type for {@code key}
   */
  <T> BindingImpl<T> createUnitializedBinding(Key<T> key, Class<T> type, Scope scope, Object source,
      LoadStrategy loadStrategy, Errors errors) throws ErrorsException {
    // Don't try to inject arrays, or enums.
    if (type.isArray() || type.isEnum()) {
      throw errors.missingImplementation(type).toException();
    }

    // Handle @ImplementedBy
    ImplementedBy implementedBy = type.getAnnotation(ImplementedBy.class);
    if (implementedBy != null) {
      Annotations.checkForMisplacedScopeAnnotations(type, source, errors);
      return createImplementedByBinding(type, scope, implementedBy, loadStrategy, errors);
    }

    // Handle @ProvidedBy.
    ProvidedBy providedBy = type.getAnnotation(ProvidedBy.class);
    if (providedBy != null) {
      Annotations.checkForMisplacedScopeAnnotations(type, source, errors);
      return createProvidedByBinding(type, scope, providedBy, loadStrategy, errors);
    }

    // We can't inject abstract classes.
    // TODO: Method interceptors could actually enable us to implement
    // abstract types. Should we remove this restriction?
    if (Modifier.isAbstract(type.getModifiers())) {
      throw errors.missingImplementation(key).toException();
    }

    // Error: Inner class.
    if (Classes.isInnerClass(type)) {
      throw errors.cannotInjectInnerClass(type).toException();
    }

    if (scope == null) {
      Class<? extends Annotation> scopeAnnotation = Annotations.findScopeAnnotation(errors, type);
      if (scopeAnnotation != null) {
        scope = scopes.get(scopeAnnotation);
        if (scope == null) {
          errors.withSource(type).scopeNotFound(scopeAnnotation);
        }
      }
    }

    Key<T> keyForRawType = Key.get(type);

    LateBoundConstructor<T> lateBoundConstructor = new LateBoundConstructor<T>();
    InternalFactory<? extends T> scopedFactory
        = Scopes.scope(keyForRawType, this, lateBoundConstructor, scope);
    return new ClassBindingImpl<T>(
        this, keyForRawType, source, scopedFactory, scope, lateBoundConstructor, loadStrategy);
  }

  static class LateBoundConstructor<T> implements InternalFactory<T> {
    ConstructorInjector<T> constructorInjector;

    @SuppressWarnings("unchecked") // the constructor T is the same as the implementation T
    void bind(InjectorImpl injector, Class<T> implementation, Errors errors)
        throws ErrorsException {
      constructorInjector = (ConstructorInjector<T>) injector.constructors.get(
          implementation, errors);
    }

    public Constructor<T> getConstructor() {
      checkState(constructorInjector != null, "Constructor is not ready");
      return constructorInjector.constructionProxy.getConstructor();
    }

    @SuppressWarnings("unchecked")
    public T get(Errors errors, InternalContext context, Dependency<?> dependency)
        throws ErrorsException {
      checkState(constructorInjector != null, "Construct before bind, " + constructorInjector);

      // This may not actually be safe because it could return a super type of T (if that's all the
      // client needs), but it should be OK in practice thanks to the wonders of erasure.
      return (T) constructorInjector.construct(
          errors, context, dependency.getKey().getRawType());
    }
  }

  /** Creates a binding for a type annotated with @ProvidedBy. */
  <T> BindingImpl<T> createProvidedByBinding(final Class<T> type, Scope scope,
      ProvidedBy providedBy, LoadStrategy loadStrategy, Errors errors) throws ErrorsException {
    final Class<? extends Provider<?>> providerType = providedBy.value();

    // Make sure it's not the same type. TODO: Can we check for deeper loops?
    if (providerType == type) {
      throw errors.recursiveProviderType().toException();
    }

    // TODO: Make sure the provided type extends type. We at least check the type at runtime below.

    // Assume the provider provides an appropriate type. We double check at runtime.
    @SuppressWarnings("unchecked")
    Key<? extends Provider<T>> providerKey = (Key<? extends Provider<T>>) Key.get(providerType);
    final BindingImpl<? extends Provider<?>> providerBinding
        = getBindingOrThrow(providerKey, errors);

    InternalFactory<T> internalFactory = new InternalFactory<T>() {
      public T get(Errors errors, InternalContext context, Dependency dependency)
          throws ErrorsException {
        Provider<?> provider = providerBinding.internalFactory.get(errors, context, dependency);
        try {
          Object o = provider.get();
          if (o != null && !type.isInstance(o)) {
            throw errors.withSource(type).subtypeNotProvided(providerType, type).toException();
          }
          @SuppressWarnings("unchecked") // protected by isInstance() check above
          T t = (T) o;
          return t;
        } catch (RuntimeException e) {
          throw errors.withSource(type).errorInProvider(e, null).toException();
        }
      }
    };

    Key<T> key = Key.get(type);
    return new LinkedProviderBindingImpl<T>(
        this,
        key,
        type,
        Scopes.<T>scope(key, this, internalFactory, scope),
        scope,
        providerKey,
        loadStrategy);
  }

  /** Creates a binding for a type annotated with @ImplementedBy. */
  <T> BindingImpl<T> createImplementedByBinding(Class<T> type, Scope scope,
      ImplementedBy implementedBy, LoadStrategy loadStrategy, Errors errors)
      throws ErrorsException {
    Class<?> implementationType = implementedBy.value();

    // Make sure it's not the same type. TODO: Can we check for deeper cycles?
    if (implementationType == type) {
      throw errors.recursiveImplementationType().toException();
    }

    // Make sure implementationType extends type.
    if (!type.isAssignableFrom(implementationType)) {
      throw errors.notASubtype(implementationType, type).toException();
    }

    // After the preceding check, this cast is safe.
    @SuppressWarnings("unchecked")
    Class<? extends T> subclass = (Class<? extends T>) implementationType;

    // Look up the target binding.
    final BindingImpl<? extends T> targetBinding = getBindingOrThrow(Key.get(subclass), errors);

    InternalFactory<T> internalFactory = new InternalFactory<T>() {
      public T get(Errors errors, InternalContext context, Dependency<?> dependency)
          throws ErrorsException {
        return targetBinding.internalFactory.get(errors, context, dependency);
      }
    };

    Key<T> key = Key.get(type);
    return new LinkedBindingImpl<T>(
        this,
        key,
        type,
        Scopes.<T>scope(key, this, internalFactory, scope),
        scope,
        Key.get(subclass),
        loadStrategy);
  }

  /**
   * Returns a new just-in-time binding created by resolving {@code key}. This could be an
   * injectable class (including those with @ImplementedBy, etc.), an automatically converted
   * constant, a {@code Provider<X>} binding, etc.
   *
   * @throws com.google.inject.internal.ErrorsException if the binding cannot be created.
   */
  <T> BindingImpl<T> createJustInTimeBinding(Key<T> key, Errors errors)
      throws ErrorsException {
    // Handle cases where T is a Provider<?>.
    if (isProvider(key)) {
      // These casts are safe. We know T extends Provider<X> and that given Key<Provider<X>>,
      // createProviderBinding() will return BindingImpl<Provider<X>>.
      @SuppressWarnings("unchecked")
      BindingImpl<T> binding
          = (BindingImpl<T>) createProviderBinding((Key) key, LoadStrategy.LAZY, errors);
      return binding;
    }

    // Try to convert a constant string binding to the requested type.
    BindingImpl<T> convertedBinding = convertConstantStringBinding(key, errors);
    if (convertedBinding != null) {
      return convertedBinding;
    }

    // If the key has an annotation...
    if (key.hasAnnotationType()) {
      // Look for a binding without annotation attributes or return null.
      if (key.hasAttributes()) {
        try {
          Errors ignored = new Errors();
          return getBindingOrThrow(key.withoutAttributes(), ignored);
        } catch (ErrorsException ignored) {
          // throw with a more appropriate message below
        }
      }
      throw errors.missingImplementation(key).toException();
    }

    // Create a binding based on the raw type.
    return createBindingFromType(key, LoadStrategy.LAZY, errors);
  }

  <T> InternalFactory<? extends T> getInternalFactory(Key<T> key, Errors errors)
      throws ErrorsException {
    return getBindingOrThrow(key, errors).internalFactory;
  }

  /** Cached field and method injectors for a type. */
  final FailableCache<Class<?>, List<SingleMemberInjector>> injectors
      = new FailableCache<Class<?>, List<SingleMemberInjector>>() {
    protected List<SingleMemberInjector> create(Class<?> type, Errors errors)
        throws ErrorsException {
      List<InjectionPoint> injectionPoints = Lists.newArrayList();
      try {
        InjectionPoint.addForInstanceMethodsAndFields(annotationProviderFactories(), type, injectionPoints);
      } catch (ConfigurationException e) {
        errors.merge(e.getErrorMessages());
      }
      ImmutableList<SingleMemberInjector> injectors = getInjectors(injectionPoints, errors);
      errors.throwIfNecessary();
      return injectors;
    }
  };

  /** Returns the injectors for the specified injection points. */
  ImmutableList<SingleMemberInjector> getInjectors(
      List<InjectionPoint> injectionPoints, Errors errors) {
    List<SingleMemberInjector> injectors = Lists.newArrayList();
    for (InjectionPoint injectionPoint : injectionPoints) {
      try {
        Errors errorsForMember = injectionPoint.isOptional()
            ? new Errors(injectionPoint)
            : errors;
        SingleMemberInjector injector = injectionPoint.getMember() instanceof Field
            ? new SingleFieldInjector(errorsForMember, this, injectionPoint)
            : new SingleMethodInjector(errorsForMember, this, injectionPoint);
        injectors.add(injector);
      } catch (ErrorsException ignoredForNow) {
      }
    }
    return ImmutableList.copyOf(injectors);
  }

  // not test-covered
  public Map<Key<?>, Binding<?>> getBindings() {
    return Collections.<Key<?>, Binding<?>>unmodifiableMap(explicitBindings);
  }

  interface SingleInjectorFactory<M extends Member & AnnotatedElement> {
    SingleMemberInjector create(InjectorImpl injector, M member, Errors errors)
        throws ErrorsException;
  }

  private static class BindingsMultimap {
    final Multimap<TypeLiteral<?>, Binding<?>> multimap = Multimaps.newArrayListMultimap();

    <T> void put(TypeLiteral<T> type, BindingImpl<T> binding) {
      multimap.put(type, binding);
    }

    // safe because we only put matching entries into the map
    @SuppressWarnings("unchecked")
    <T> List<Binding<T>> getAll(TypeLiteral<T> type) {
      return (List<Binding<T>>) (List) multimap.get(type);
    }
  }

  InternalFactory<?> createCustomInternalFactory(final AnnotatedElement annotatedElement, final AnnotationProviderFactory<?> customFactory) {
    return new InternalFactoryToProviderAdapter(new Provider() {
      public Object get() {
        Provider<?> provider = customFactory.createProvider(annotatedElement);
       return provider.get();
      }
      @Override public String toString() {
        return customFactory.toString();
      }
    });
  }

  class SingleFieldInjector implements SingleMemberInjector {
    final Field field;
    final InternalFactory<?> factory;
    final InjectionPoint injectionPoint;
    final Dependency<?> dependency;

    public SingleFieldInjector(Errors errors, InjectorImpl injector, InjectionPoint injectionPoint)
        throws ErrorsException {
      this.injectionPoint = injectionPoint;
      this.field = (Field) injectionPoint.getMember();
      this.dependency = injectionPoint.getDependencies().get(0);

      // Ewwwww...
      field.setAccessible(true);

      final AnnotationProviderFactory<?> customFactory = injectionPoint.getAnnotationProviderFactory();
      if (customFactory != null) {
        factory = createCustomInternalFactory(field, customFactory);
      }
      else {
        factory = injector.getInternalFactory(dependency.getKey(), errors.withSource(dependency));
      }
    }

    public InjectionPoint getInjectionPoint() {
      return injectionPoint;
    }

    public void inject(Errors errors, InternalContext context, Object o) {
      context.setDependency(dependency);
      errors.pushSource(dependency);
      try {
        Object value = factory.get(errors, context, dependency);
        field.set(o, value);
      }
      catch (IllegalAccessException e) {
        throw new AssertionError(e);
      }
      catch (ErrorsException e) {
        errors.merge(e.getErrors());
      }
      finally {
        context.setDependency(null);
        errors.popSource(dependency);
      }
    }
  }

  /**
   * Returns parameter injectors, or {@code null} if there are no parameters.
   */
  SingleParameterInjector<?>[] getParametersInjectors(InjectionPoint injectionPoint, Errors errors)
      throws ErrorsException {
    errors.pushSource(injectionPoint);
    try {
      Member member = injectionPoint.getMember();
      Annotation misplacedBindingAnnotation = Annotations.findBindingAnnotation(
          errors, member, ((AnnotatedElement) member).getAnnotations());
      if (misplacedBindingAnnotation != null) {
        errors.misplacedBindingAnnotation(member, misplacedBindingAnnotation);
      }
    } finally {
      errors.popSource(injectionPoint);
    }

    List<Dependency<?>> parameters = injectionPoint.getDependencies();
    if (parameters.isEmpty()) {
      return null;
    }

    SingleParameterInjector<?>[] parameterInjectors
        = new SingleParameterInjector<?>[parameters.size()];
    int index = 0;
    AnnotationProviderFactory<?> customFactory = injectionPoint.getAnnotationProviderFactory();
    if (parameters.size() == 1 && customFactory != null) {
      InternalFactory<?> internalFactory = createCustomInternalFactory(
          (AnnotatedElement) injectionPoint.getMember(), customFactory);
      parameterInjectors[0] = new SingleParameterInjector(injectionPoint.getDependencies().get(0), internalFactory);      
    }
    else {
      for (Dependency<?> parameter : parameters) {
        errors.pushSource(parameter);
        try {
          parameterInjectors[index] = createParameterInjector(parameter, errors);
        } catch (ErrorsException rethrownBelow) {
          // rethrown below
        } finally {
          errors.popSource(parameter);
        }
        index++;
      }
    }

    errors.throwIfNecessary();
    return parameterInjectors;
  }

  <T> SingleParameterInjector<T> createParameterInjector(final Dependency<T> dependency,
      final Errors errors) throws ErrorsException {
    InternalFactory<? extends T> factory
        = getInternalFactory(dependency.getKey(), errors.withSource(dependency));
    return new SingleParameterInjector<T>(dependency, factory);
  }

  static class SingleMethodInjector implements SingleMemberInjector {
    final MethodInvoker methodInvoker;
    final SingleParameterInjector<?>[] parameterInjectors;
    final InjectionPoint injectionPoint;

    public SingleMethodInjector(Errors errors, InjectorImpl injector, InjectionPoint injectionPoint)
        throws ErrorsException {
      this.injectionPoint = injectionPoint;
      final Method method = (Method) injectionPoint.getMember();

      // We can't use FastMethod if the method is private.
      if (Modifier.isPrivate(method.getModifiers())
          || Modifier.isProtected(method.getModifiers())) {
        method.setAccessible(true);
        methodInvoker = new MethodInvoker() {
          public Object invoke(Object target, Object... parameters)
              throws IllegalAccessException, InvocationTargetException {
            return method.invoke(target, parameters);
          }
        };
      } else {
        FastClass fastClass = newFastClass(method.getDeclaringClass(),
            Visibility.forMember(method));
        final FastMethod fastMethod = fastClass.getMethod(method);

        methodInvoker = new MethodInvoker() {
          public Object invoke(Object target, Object... parameters)
              throws IllegalAccessException, InvocationTargetException {
            return fastMethod.invoke(target, parameters);
          }
        };
      }

      parameterInjectors = injector.getParametersInjectors(injectionPoint, errors);
    }

    public void inject(Errors errors, InternalContext context, Object o) {
      try {
        Object[] parameters = getParameters(errors, context, parameterInjectors);
        methodInvoker.invoke(o, parameters);
      } catch (IllegalAccessException e) {
        throw new AssertionError(e);
      } catch (InvocationTargetException userException) {
        Throwable cause = userException.getCause() != null
            ? userException.getCause()
            : userException;
        errors.withSource(injectionPoint).errorInjectingMethod(cause);
      } catch (ErrorsException e) {
        errors.merge(e.getErrors());
      }
    }

    public InjectionPoint getInjectionPoint() {
      return injectionPoint;
    }
  }

  /** Invokes a method. */
  interface MethodInvoker {
    Object invoke(Object target, Object... parameters)
        throws IllegalAccessException, InvocationTargetException;
  }

  /** Cached constructor injectors for each type */
  final FailableCache<Class<?>, ConstructorInjector<?>> constructors
      = new FailableCache<Class<?>, ConstructorInjector<?>>() {
    @SuppressWarnings("unchecked")
    protected ConstructorInjector<?> create(Class<?> key, Errors errors) throws ErrorsException {
      return new ConstructorInjector(errors, InjectorImpl.this, key);
    }
  };

  static class SingleParameterInjector<T> {
    final Dependency<T> dependency;
    final InternalFactory<? extends T> factory;

    public SingleParameterInjector(Dependency<T> dependency,
        InternalFactory<? extends T> factory) {
      this.dependency = dependency;
      this.factory = factory;
    }

    T inject(Errors errors, InternalContext context) throws ErrorsException {
      context.setDependency(dependency);
      errors.pushSource(dependency);
      try {
        return factory.get(errors, context, dependency);
      }
      finally {
        errors.popSource(dependency);
        context.setDependency(null);
      }
    }
  }

  /** Iterates over parameter injectors and creates an array of parameter values. */
  static Object[] getParameters(Errors errors, InternalContext context,
      SingleParameterInjector[] parameterInjectors) throws ErrorsException {
    if (parameterInjectors == null) {
      return null;
    }

    Object[] parameters = new Object[parameterInjectors.length];
    for (int i = 0; i < parameters.length; i++) {
      try {
        parameters[i] = parameterInjectors[i].inject(errors, context);
      } catch (ErrorsException e) {
        errors.merge(e.getErrors());
      }
    }

    errors.throwIfNecessary();
    return parameters;
  }

  void injectMembers(Errors errors, Object o, InternalContext context)
      throws ErrorsException {
    if (o == null) {
      return;
    }

    for (SingleMemberInjector injector : injectors.get(o.getClass(), errors)) {
      injector.inject(errors, context, o);
    }
  }

  // Not test-covered
  public void injectMembers(final Object o) {
    Errors errors = new Errors();
    try {
      injectMembersOrThrow(errors, o);
    } catch (ErrorsException e) {
      errors.merge(e.getErrors());
    }

    ProvisionException.throwNewIfNonEmpty(errors);
  }

  public void injectMembersOrThrow(final Errors errors, final Object o)
      throws ErrorsException {
    callInContext(new ContextualCallable<Void>() {
      public Void call(InternalContext context) throws ErrorsException {
        injectMembers(errors, o, context);
        return null;
      }
    });
  }

  public <T> Provider<T> getProvider(Class<T> type) {
    return getProvider(Key.get(type));
  }

  <T> Provider<T> getProviderOrThrow(final Key<T> key, Errors errors) throws ErrorsException {
    final InternalFactory<? extends T> factory = getInternalFactory(key, errors);

    return new CloseableProvider<T>() {
      public T get() {
        final Errors errors = new Errors();
        try {
          T t = callInContext(new ContextualCallable<T>() {
            public T call(InternalContext context) throws ErrorsException {
              Dependency<T> dependency = Dependency.get(key);
              context.setDependency(dependency);
              errors.pushSource(dependency);
              try {
                return factory.get(errors, context, dependency);
              }
              finally {
                context.setDependency(null);
                errors.popSource(dependency);
              }
            }
          });
          errors.throwIfNecessary();
          return t;
        } catch (ErrorsException e) {
          throw new ProvisionException(errors.merge(e.getErrors()));
        }
      }

      public void close(Closer closer, CloseErrors errors) {
        if (factory instanceof Closeable) {
          Closeable closeable = (Closeable) factory;
          closeable.close(closer, errors);
        }
      }

      @Override public String toString() {
        return factory.toString();
      }
    };
  }

  public <T> Provider<T> getProvider(final Key<T> key) {
    Errors errors = new Errors(key.getRawType());
    try {
      Provider<T> result = getProviderOrThrow(key, errors);
      errors.throwIfNecessary();
      return result;
    }
    catch (ErrorsException e) {
      throw new ProvisionException(errors.merge(e.getErrors()));
    }
  }

  public <T> T getInstance(Key<T> key) {
    return getProvider(key).get();
  }

  public <T> T getInstance(Class<T> type) {
    return getProvider(type).get();
  }

  final ThreadLocal<InternalContext[]> localContext = new ThreadLocal<InternalContext[]>() {
    protected InternalContext[] initialValue() {
      return new InternalContext[1];
    }
  };

  /** Looks up thread local context. Creates (and removes) a new context if necessary. */
  <T> T callInContext(ContextualCallable<T> callable) throws ErrorsException {
    InternalContext[] reference = localContext.get();
    if (reference[0] == null) {
      reference[0] = new InternalContext(this);
      try {
        return callable.call(reference[0]);
      } finally {
        // Only remove the context if this call created it.
        localContext.remove();
      }
    }
    else {
      // Someone else will clean up this context.
      return callable.call(reference[0]);
    }
  }

  /** Injects a field or method in a given object. */
  public interface SingleMemberInjector {
    void inject(Errors errors, InternalContext context, Object o);
    InjectionPoint getInjectionPoint();
  }

  public String toString() {
    return new ToStringBuilder(Injector.class)
        .add("bindings", explicitBindings)
        .toString();
  }

  /** Iterates through all bindings closing any {@link Closeable} providers which have pre destroy hooks */
  public void close() throws CloseFailedException {
    Set<Closer> closers = getInstancesOf(Closer.class);
    if (closers.isEmpty()) {
      return;
    }
    Closer closer = new CompositeCloser(closers);
    CloseErrorsImpl errors = new CloseErrorsImpl(this);

    Set<Entry<Key<?>,Binding<?>>> entries = getBindings().entrySet();
    for (Entry<Key<?>, Binding<?>> entry : entries) {
      Binding<?> binding = entry.getValue();
      Provider<?> provider = binding.getProvider();
      if (provider instanceof Closeable) {
        Closeable closeable = (Closeable) provider;
        closeable.close(closer, errors);
      }
    }

    errors.throwIfNecessary();
  }

  /**
   * Returns a collection of all instances of the given base type
   * @param baseClass the base type of objects required
   * @param <T> the base type
   * @return a set of objects returned from this injector
   */
  public <T> Set<T> getInstancesOf(Class<T> baseClass) {
    Set<T> answer = Sets.newHashSet();
    Set<Entry<Key<?>, Binding<?>>> entries = getBindings().entrySet();
    for (Entry<Key<?>, Binding<?>> entry : entries) {
      Key<?> key = entry.getKey();
      if (baseClass.isAssignableFrom(key.getRawType())) {
        Object value = getInstance(key);
        if (value != null) {
          T castValue = baseClass.cast(value);
          answer.add(castValue);
        }
      }
    }
    return answer;
  }

  /**
   * Returns a collection of all instances matching the given matcher
   * @param matcher matches the types to return instances
   * @return a set of objects returned from this injector
   */
  public <T> Set<T> getInstancesOf(Matcher<Class<T>> matcher) {
    Set<T> answer = Sets.newHashSet();
    Set<Entry<Key<?>, Binding<?>>> entries = getBindings().entrySet();
    for (Entry<Key<?>, Binding<?>> entry : entries) {
      Key<?> key = entry.getKey();
      if (matcher.matches((Class<T>) key.getRawType())) {
        Object value = getInstance(key);
        answer.add((T) value);
      }
    }
    return answer;
  }


  /**
   * Finds all the instances of the {@link com.google.inject.spi.AnnotationProviderFactory} instances
   * which are registered to provide custom annotation driven injection points
   */
  public Map<Class<? extends Annotation>, AnnotationProviderFactory> annotationProviderFactories() {
    Map<Class<? extends Annotation>, AnnotationProviderFactory> answer = Maps.newHashMap();
    Set<Entry<Key<?>, Binding<?>>> entries = getBindings().entrySet();
    for (Entry<Key<?>, Binding<?>> entry : entries) {
      final Key<?> key = entry.getKey();
      final Class<?> type = key.getRawType();
      if (AnnotationProviderFactory.class.isAssignableFrom(type)) {
        InjectionAnnotation injectionAnnotation = type.getAnnotation(InjectionAnnotation.class);
        if (injectionAnnotation != null) {
          Class<? extends Annotation> annotationType = injectionAnnotation.value();

          // let avoid injecting anything yet
          AnnotationProviderFactory factory = new AnnotationProviderFactory() {
            public Class getAnnotationType() {
              return type;
            }

            public Provider createProvider(final AnnotatedElement member) {
              return new Provider() {
                public Object get() {
                  AnnotationProviderFactory factory = (AnnotationProviderFactory) getInstance(key);
                  Objects.nonNull(factory, "Should have an instance for key " + key);
                  return factory.createProvider(member).get();
                }

                @Override public String toString() {
                  return "AnnotationProvider:" + key + " using " + type.getName();
                }
              };
            }
          };
          answer.put(annotationType, factory);
        }
      }
    }
    return answer;
  }
}
