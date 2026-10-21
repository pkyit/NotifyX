/**
 * NotifyX Spring Boot 3 Starter：业务方唯一需要声明的依赖。
 *
 * <p>本模块只做依赖聚合（见设计书 ADR-001 / §5.1），唯一的 Java 类型是
 * {@link io.github.pkyit.notifyx.starter.NotifyxStarter}——一个不可实例化的标记类，
 * 用来让 Maven Central 要求的 {@code -sources.jar} / {@code -javadoc.jar} 有产物可附。
 * 空模块不产生这两个附件时，Central 会拒绝发布。</p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * <dependency>
 *     <groupId>io.github.pkyit</groupId>
 *     <artifactId>notifyx-spring-boot3-starter</artifactId>
 *     <version>1.0.0</version>
 * </dependency>
 * }</pre>
 *
 * @since 1.0
 */
package io.github.pkyit.notifyx.starter;
