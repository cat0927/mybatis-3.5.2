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

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 * @author Kazuki Shimizu
 *
 *
 *  对 Mapper 方法的封装。
 */
public class MapperMethod {

  // sql 类型
  private final SqlCommand command;

  // 获取方法的签名信息（维护 Mapper 接口中方法的相关信息）
  private final MethodSignature method;

  public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {

    /**
     *  获取SQL 语句的类型和 Mapper的 ID 等信息 {@link SqlCommand#SqlCommand(Configuration, Class, Method)}
     *    1、Mapper 如果方法就定义在当前接口中，则证明没有对应的SQL,"抛出异常"。
     *
     *  获取方法的签名信息。 {@link MethodSignature#MethodSignature(Configuration, Class, Method)}
     *    1、例如 Mapper 方法的参数名、参数注解。
     */
    this.command = new SqlCommand(config, mapperInterface, method);
    this.method = new MethodSignature(config, mapperInterface, method);
  }

  /**
   *  根据要执行的 SQL 语句的具体类型执行 SQLSession 的相应的方法完成数据库操作。
   *
   * @param sqlSession
   * @param args
   * @return
   */
  public Object execute(SqlSession sqlSession, Object[] args) {
    Object result;

    // 获取 SQL 类型
    switch (command.getType()) {
      case INSERT: {

        /**
         * 提取参数信息。{@link ParamNameResolver#getNamedParams(Object[])}
         */
        Object param = method.convertArgsToSqlCommandParam(args);

        /**
         * 调用 SQLSession的 insert 方法，调用 `rowCountResult` 方法统计行数。
         *  {@link org.apache.ibatis.session.defaults.DefaultSqlSession#insert(String)}
         *  {@link #rowCountResult(int)}
         */
        result = rowCountResult(sqlSession.insert(command.getName(), param));
        break;
      }
      case UPDATE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.update(command.getName(), param));
        break;
      }
      case DELETE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.delete(command.getName(), param));
        break;
      }
      case SELECT:
        if (method.returnsVoid() && method.hasResultHandler()) {

          /**
           *  如果方法返回值 为 void，且参数中包含 ResultHandler 类型的实参，
           *  则查询的结果集，将会由 ResultHandler 对象进行处理 {@link #executeWithResultHandler(SqlSession, Object[])}
           */
          executeWithResultHandler(sqlSession, args);
          result = null;
        } else if (method.returnsMany()) {

          /**
           *  返回多行，Result {@link #executeForMany(SqlSession, Object[])}
           */
          result = executeForMany(sqlSession, args);
        } else if (method.returnsMap()) {

          /**
           * 返回 Map 类型 {@link #executeForMap(SqlSession, Object[])}
           */
          result = executeForMap(sqlSession, args);
        } else if (method.returnsCursor()) {

          /**
           * 返回 Cursor 类型 {@link #executeForCursor(SqlSession, Object[])}
           */
          result = executeForCursor(sqlSession, args);
        } else {
          Object param = method.convertArgsToSqlCommandParam(args);
          result = sqlSession.selectOne(command.getName(), param);
          if (method.returnsOptional()
              && (result == null || !method.getReturnType().equals(result.getClass()))) {
            result = Optional.ofNullable(result);
          }
        }
        break;
      case FLUSH:
        result = sqlSession.flushStatements();
        break;
      default:
        throw new BindingException("Unknown execution method for: " + command.getName());
    }
    if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
      throw new BindingException("Mapper method '" + command.getName()
          + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
    }
    return result;
  }

  /**
   * 统计行数
   *
   * @param rowCount
   * @return
   */
  private Object rowCountResult(int rowCount) {
    final Object result;
    if (method.returnsVoid()) {
      result = null;
    } else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
      result = rowCount;
    } else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
      result = (long)rowCount;
    } else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
      result = rowCount > 0;
    } else {
      throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: " + method.getReturnType());
    }
    return result;
  }

  private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
    MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
    if (!StatementType.CALLABLE.equals(ms.getStatementType())
        && void.class.equals(ms.getResultMaps().get(0).getType())) {
      throw new BindingException("method " + command.getName()
          + " needs either a @ResultMap annotation, a @ResultType annotation,"
          + " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
    }
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
    } else {
      sqlSession.select(command.getName(), param, method.extractResultHandler(args));
    }
  }

  private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
    List<E> result;

    //param是我们传入的参数，如果传入的是Map，那么这个实际上就是Map对象
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {

      //如果有分页
      RowBounds rowBounds = method.extractRowBounds(args);

      /**
       *  执行SQL的位置 {@link org.apache.ibatis.session.defaults.DefaultSqlSession#selectList(String, Object, RowBounds)}
       */
      result = sqlSession.selectList(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.selectList(command.getName(), param);
    }
    // issue #510 Collections & arrays support
    if (!method.getReturnType().isAssignableFrom(result.getClass())) {
      if (method.getReturnType().isArray()) {
        return convertToArray(result);
      } else {
        return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
      }
    }
    return result;
  }

  private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
    Cursor<T> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectCursor(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.selectCursor(command.getName(), param);
    }
    return result;
  }

  private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
    Object collection = config.getObjectFactory().create(method.getReturnType());
    MetaObject metaObject = config.newMetaObject(collection);
    metaObject.addAll(list);
    return collection;
  }

  @SuppressWarnings("unchecked")
  private <E> Object convertToArray(List<E> list) {
    Class<?> arrayComponentType = method.getReturnType().getComponentType();
    Object array = Array.newInstance(arrayComponentType, list.size());
    if (arrayComponentType.isPrimitive()) {
      for (int i = 0; i < list.size(); i++) {
        Array.set(array, i, list.get(i));
      }
      return array;
    } else {
      return list.toArray((E[])array);
    }
  }

  private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
    Map<K, V> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey(), rowBounds);
    } else {
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey());
    }
    return result;
  }

  public static class ParamMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = -2212268410512043556L;

    @Override
    public V get(Object key) {
      if (!super.containsKey(key)) {
        throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
      }
      return super.get(key);
    }

  }

  /**
   * 获取SQL 语句的类型和 Mapper的 ID 等信息
   */
  public static class SqlCommand {

    // MapperId（唯一标识）
    private final String name;

    // SQL 类型
    private final SqlCommandType type;

    public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {

      // 获取 Mapper 接口中对应的 方法名称
      final String methodName = method.getName();

      // 获取声明该方法的类或接口的 Class对象。
      final Class<?> declaringClass = method.getDeclaringClass();

      /**
       *  将 Mapper 接口名称 和方法名拼接起来作为 SQL 语句唯一标识
       *  到 configuration 这个全局配置对象中查找 SQL 语句
       *  mappedStatement 对象就是 mapper.xml 配置文件一条 SQL 语句解析之后得到对象。
       *
       *  描述 Mapper SQL 配置信息 {@link #resolveMappedStatement(Class, String, Class, Configuration)}
       */
      MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass,
          configuration);
      if (ms == null) {

        // 针对 @Flush 注解的处理。
        if (method.getAnnotation(Flush.class) != null) {
          name = null;
          type = SqlCommandType.FLUSH;
        } else {

          // mapper 接口，没找到对应 mapper.xml 则抛出异常。
          throw new BindingException("Invalid bound statement (not found): "
              + mapperInterface.getName() + "." + methodName);
        }
      } else {

        // 记录SQL 语句的唯一标识。
        name = ms.getId();

        // 记录SQL 语句的操作类型。
        type = ms.getSqlCommandType();
        if (type == SqlCommandType.UNKNOWN) {
          throw new BindingException("Unknown execution method for: " + name);
        }
      }
    }

    public String getName() {
      return name;
    }

    public SqlCommandType getType() {
      return type;
    }

    /**
     * 根据接口的 全限类名和方法名。获取对应的 MappedStatement 对象。
     * @param mapperInterface
     * @param methodName
     * @param declaringClass
     * @param configuration
     * @return
     */
    private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName,
        Class<?> declaringClass, Configuration configuration) {

      // 获取 mapper Id
      String statementId = mapperInterface.getName() + "." + methodName;
      if (configuration.hasStatement(statementId)) {

        // 如果 Configuration 对象已注册 MappedStatement 对象，则获取 `MappedStatement` 对象。
        return configuration.getMappedStatement(statementId);
      } else if (mapperInterface.equals(declaringClass)) {

        // 如果方法就定义在当前接口中，则证明没有对应的SQL 语句，返回null。
        return null;
      }

      /**
       *
       * 如果当前检查的Mapper接口(mapperInterface)中不是定义该方法的接口(declaringClass)，
       * 则会从mapperInterface开始，沿着继承关系向上查找递归每个接口，
       * 查找该方法对应的 MappedStatement 对象。
       */
      for (Class<?> superInterface : mapperInterface.getInterfaces()) {
        if (declaringClass.isAssignableFrom(superInterface)) {
          MappedStatement ms = resolveMappedStatement(superInterface, methodName,
              declaringClass, configuration);
          if (ms != null) {
            return ms;
          }
        }
      }
      return null;
    }
  }


  /**
   * 获取方法的签名信息
   */
  public static class MethodSignature {

    private final boolean returnsMany;
    private final boolean returnsMap;
    private final boolean returnsVoid;
    private final boolean returnsCursor;
    private final boolean returnsOptional;

    // 方法返回的具体类型
    private final Class<?> returnType;

    // 如果方法的返回值为 Map 集合，则 mapkey 字段记录作为 key 的列名。
    private final String mapKey;

    // Mapper 接口方法的参数列表中 ResultHandler 类型
    private final Integer resultHandlerIndex;

    // Mapper 接口方法参数列表，RowBounds 类型
    private final Integer rowBoundsIndex;

    // 用来解析方法参数列表工具类
    private final ParamNameResolver paramNameResolver;

    public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {

      // 获取方法返回值。
      Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
      if (resolvedReturnType instanceof Class<?>) {
        this.returnType = (Class<?>) resolvedReturnType;
      } else if (resolvedReturnType instanceof ParameterizedType) {
        this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
      } else {
        this.returnType = method.getReturnType();
      }

      // 返回是 void 类型
      this.returnsVoid = void.class.equals(this.returnType);

      // 返回是 集合类型。
      this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();

      // 返回 cursor 类型
      this.returnsCursor = Cursor.class.equals(this.returnType);

      // 返回 Optional 类型
      this.returnsOptional = Optional.class.equals(this.returnType);
      this.mapKey = getMapKey(method);
      this.returnsMap = this.mapKey != null;

      // rowBoundIndex 位置索引
      this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
      this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);

      /**
       * 解析 Mapper 方法参数。{@link ParamNameResolver#ParamNameResolver(Configuration, Method)}
       */
      this.paramNameResolver = new ParamNameResolver(configuration, method);
    }

    public Object convertArgsToSqlCommandParam(Object[] args) {
      return paramNameResolver.getNamedParams(args);
    }

    public boolean hasRowBounds() {
      return rowBoundsIndex != null;
    }

    public RowBounds extractRowBounds(Object[] args) {
      return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
    }

    public boolean hasResultHandler() {
      return resultHandlerIndex != null;
    }

    public ResultHandler extractResultHandler(Object[] args) {
      return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
    }

    public String getMapKey() {
      return mapKey;
    }

    public Class<?> getReturnType() {
      return returnType;
    }

    public boolean returnsMany() {
      return returnsMany;
    }

    public boolean returnsMap() {
      return returnsMap;
    }

    public boolean returnsVoid() {
      return returnsVoid;
    }

    public boolean returnsCursor() {
      return returnsCursor;
    }

    /**
     * return whether return type is {@code java.util.Optional}.
     * @return return {@code true}, if return type is {@code java.util.Optional}
     * @since 3.5.0
     */
    public boolean returnsOptional() {
      return returnsOptional;
    }

    private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
      Integer index = null;
      final Class<?>[] argTypes = method.getParameterTypes();
      for (int i = 0; i < argTypes.length; i++) {
        if (paramType.isAssignableFrom(argTypes[i])) {
          if (index == null) {
            index = i;
          } else {
            throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
          }
        }
      }
      return index;
    }

    private String getMapKey(Method method) {
      String mapKey = null;
      if (Map.class.isAssignableFrom(method.getReturnType())) {
        final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
        if (mapKeyAnnotation != null) {
          mapKey = mapKeyAnnotation.value();
        }
      }
      return mapKey;
    }
  }

}
