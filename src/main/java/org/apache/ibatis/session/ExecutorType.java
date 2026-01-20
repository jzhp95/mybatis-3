/**
 *    Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.session;

/**
 * @author Clinton Begin
 */
public enum ExecutorType {
  // 简单执行器类型：每次执行SQL语句都会创建一个新的PreparedStatement，执行完毕后关闭
  SIMPLE,
  // 重用执行器类型：会重用PreparedStatement对象，对于相同SQL语句的多次执行，会复用已创建的PreparedStatement
  REUSE,
  // 批处理执行器类型：将多个SQL语句批量执行，提高数据库操作效率
  BATCH
}

