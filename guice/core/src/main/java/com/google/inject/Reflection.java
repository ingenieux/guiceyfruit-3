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


package com.google.inject;

import com.google.inject.internal.Errors;
import com.google.inject.internal.ErrorsException;

/**
 * Abstraction for Java's reflection APIs. This interface exists to provide a
 * single place where runtime reflection can be substituted for another
 * mechanism such as CGLib or compile-time code generation.
 *
 * @author jessewilson@google.com (Jesse Wilson)
 */
interface Reflection {

  public <T> ConstructionProxy<T> getConstructionProxy(Errors errors, Class<T> implementation)
      throws ErrorsException;

  interface Factory {
    Reflection create(ConstructionProxyFactory constructionProxyFactory);
  }
}
