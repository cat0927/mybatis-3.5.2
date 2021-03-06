/**
 *    Copyright 2009-2020 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.binding;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;

import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 *
 *  MapperProxy 是生成 Mapper 接口代理对象的关键。
 */
public class MapperProxy<T> implements InvocationHandler, Serializable {

  private static final long serialVersionUID = -6424540398559729838L;

  // 记录当前 MapperProxy 管理的 sqlSession 对象。
  private final SqlSession sqlSession;

  // Mapper 接口类型（也是当前 MapperProxy 关联的代理对象实现的接口类型）
  private final Class<T> mapperInterface;

  // 用于缓存，MapperMethodInvoker 对象的集合，key: 是Mapper 接口中的方法，value: 对应 MapperMetrhodInvoker 对象。
  private final Map<Method, MapperMethod> methodCache;

  public MapperProxy(SqlSession sqlSession, Class<T> mapperInterface, Map<Method, MapperMethod> methodCache) {
    this.sqlSession = sqlSession;
    this.mapperInterface = mapperInterface;
    this.methodCache = methodCache;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {

      // 从Object 类继承的方法不处理
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, args);
      } else if (method.isDefault()) {

        /**
         *  “invokeDefaultMethod” {@link #invokeDefaultMethod(Object, Method, Object[])}
         */
        return invokeDefaultMethod(proxy, method, args);
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }

    /**
     * 对 Mapper 接口中定义的方法进行封装，生成 MapperMethod 对象。{@link #cachedMapperMethod(Method)}
     */
    final MapperMethod mapperMethod = cachedMapperMethod(method);

    /**
     * 方法执行 {@link MapperMethod#execute(SqlSession, Object[])}
     */
    return mapperMethod.execute(sqlSession, args);
  }

  /**
   *  首先从缓存中获取，如果获取不到，则创建 MapperMethod 对象。然后添加到缓存中。（这就是享元思路）
   * @param method
   * @return
   *
   *  {@link MapperMethod#MapperMethod(Class, Method, Configuration)}
   */
  private MapperMethod cachedMapperMethod(Method method) {
    return methodCache.computeIfAbsent(method, k -> new MapperMethod(mapperInterface, method, sqlSession.getConfiguration()));
  }


  /**
   *  MethodHandle 基本功能与 反射 Method 类似，但它比反射更加灵活。
   *    反射是 JAVA API 层面支持的一种机制。
   *    MethodHandle 则是 JVM 层支持的机制，相较而言，反射更加重量级。MethodHandle 更轻量级。
   *
   *
   * @param proxy
   * @param method
   * @param args
   * @return
   * @throws Throwable
   */
  private Object invokeDefaultMethod(Object proxy, Method method, Object[] args)
      throws Throwable {
    final Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class
        .getDeclaredConstructor(Class.class, int.class);
    if (!constructor.isAccessible()) {
      constructor.setAccessible(true);
    }
    final Class<?> declaringClass = method.getDeclaringClass();
    return constructor
        .newInstance(declaringClass,
            MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED
                | MethodHandles.Lookup.PACKAGE | MethodHandles.Lookup.PUBLIC)
        .unreflectSpecial(method, declaringClass).bindTo(proxy).invokeWithArguments(args);
  }
}
