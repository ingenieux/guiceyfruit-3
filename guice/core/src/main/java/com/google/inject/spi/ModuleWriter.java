/**
 * Copyright (C) 2008 Google Inc.
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

package com.google.inject.spi;

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.inject.Binder;
import com.google.inject.Binding;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.Scope;
import com.google.inject.binder.LinkedBindingBuilder;
import com.google.inject.binder.ScopedBindingBuilder;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Set;
import org.aopalliance.intercept.MethodInterceptor;

/**
 * Creates a Module from a collection of component elements.
 *
 * @author jessewilson@google.com (Jesse Wilson)
 */
public class ModuleWriter {

  /**
   * Returns a module that executes the specified elements
   * using this executing visitor.
   */
  public final Module create(final Iterable<? extends Element> elements) {
    return new Module() {
      public void configure(Binder binder) {
        apply(binder, elements);
      }
    };
  }

  /**
   * Replays {@code elements} against {@code binder}.
   */
  public final void apply(final Binder binder, Iterable<? extends Element> elements) {
    checkNotNull(binder, "binder");
    checkNotNull(elements, "elements");

    ElementVisitor<Void> visitor = new ElementVisitor<Void>() {

      public Void visitMessage(Message message) {
        writeMessage(binder, message);
        return null;
      }

      public Void visitInterceptorBinding(InterceptorBinding element) {
        writeBindInterceptor(binder, element);
        return null;
      }

      public Void visitScopeBinding(ScopeBinding element) {
        writeBindScope(binder, element);
        return null;
      }

      public Void visitInjectionRequest(InjectionRequest element) {
        writeRequestInjection(binder, element);
        return null;
      }

      public Void visitStaticInjectionRequest(StaticInjectionRequest element) {
        writeRequestStaticInjection(binder, element);
        return null;
      }

      public Void visitTypeConverterBinding(TypeConverterBinding element) {
        writeConvertToTypes(binder, element);
        return null;
      }

      public <T> Void visitBinding(Binding<T> element) {
        writeBind(binder, element);
        return null;
      }

      public <T> Void visitProviderLookup(ProviderLookup<T> element) {
        writeGetProvider(binder, element);
        return null;
      }
    };

    for (Element element : elements) {
      element.acceptVisitor(visitor);
    }
  }

  protected void writeMessage(final Binder binder, final Message element) {
    binder.addError(element);
  }

  protected void writeBindInterceptor(final Binder binder, final InterceptorBinding element) {
    List<MethodInterceptor> interceptors = element.getInterceptors();
    binder.withSource(element.getSource()).bindInterceptor(
        element.getClassMatcher(), element.getMethodMatcher(),
        interceptors.toArray(new MethodInterceptor[interceptors.size()]));
  }

  protected void writeBindScope(final Binder binder, final ScopeBinding element) {
    binder.withSource(element.getSource()).bindScope(
        element.getAnnotationType(), element.getScope());
  }

  protected void writeRequestInjection(final Binder binder,
      final InjectionRequest command) {
    binder.withSource(command.getSource()).requestInjection(command.getInstance());
  }

  protected void writeRequestStaticInjection(final Binder binder,
      final StaticInjectionRequest element) {
    Class<?> type = element.getType();
    binder.withSource(element.getSource()).requestStaticInjection(type);
  }

  protected void writeConvertToTypes(final Binder binder, final TypeConverterBinding element) {
    binder.withSource(element.getSource())
        .convertToTypes(element.getTypeMatcher(), element.getTypeConverter());
  }

  protected <T> void writeBind(final Binder binder, final Binding<T> element) {
    LinkedBindingBuilder<T> lbb = binder.withSource(element.getSource()).bind(element.getKey());

    ScopedBindingBuilder sbb = applyTarget(element, lbb);
    applyScoping(element, sbb);
  }

  /**
   * Execute this target against the linked binding builder.
   */
  protected <T> ScopedBindingBuilder applyTarget(Binding<T> binding,
      final LinkedBindingBuilder<T> linkedBindingBuilder) {
    return binding.acceptTargetVisitor(new BindingTargetVisitor<T, ScopedBindingBuilder>() {
      public ScopedBindingBuilder visitInstance(T instance, Set<InjectionPoint> injectionPoints) {
        linkedBindingBuilder.toInstance(instance);
        return null;
      }

      public ScopedBindingBuilder visitProvider(Provider<? extends T> provider,
          Set<InjectionPoint> injectionPoints) {
        return linkedBindingBuilder.toProvider(provider);
      }

      public ScopedBindingBuilder visitProviderKey(Key<? extends Provider<? extends T>> providerKey) {
        return linkedBindingBuilder.toProvider(providerKey);
      }

      public ScopedBindingBuilder visitKey(Key<? extends T> key) {
        return linkedBindingBuilder.to(key);
      }

      public ScopedBindingBuilder visitUntargetted() {
        return linkedBindingBuilder;
      }

      public ScopedBindingBuilder visitConvertedConstant(T value) {
        throw new IllegalArgumentException("Non-module element");
      }

      public ScopedBindingBuilder visitConstructor(Constructor<? extends T> constructor,
          Set<InjectionPoint> injectionPoints) {
        throw new IllegalArgumentException("Non-module element");
      }

      public ScopedBindingBuilder visitProviderBinding(Key<?> provided) {
        throw new IllegalArgumentException("Non-module element");
      }
    });
  }

  protected void applyScoping(Binding<?> binding, final ScopedBindingBuilder scopedBindingBuilder) {
    binding.acceptScopingVisitor(new BindingScopingVisitor<Void>() {
      public Void visitEagerSingleton() {
        scopedBindingBuilder.asEagerSingleton();
        return null;
      }

      public Void visitScope(Scope scope) {
        scopedBindingBuilder.in(scope);
        return null;
      }

      public Void visitScopeAnnotation(Class<? extends Annotation> scopeAnnotation) {
        scopedBindingBuilder.in(scopeAnnotation);
        return null;
      }

      public Void visitNoScoping() {
        // do nothing
        return null;
      }
    });
  }

  protected <T> void writeGetProvider(final Binder binder, final ProviderLookup<T> element) {
    Provider<T> provider = binder.withSource(element.getSource()).getProvider(element.getKey());
    element.initDelegate(provider);
  }
}
