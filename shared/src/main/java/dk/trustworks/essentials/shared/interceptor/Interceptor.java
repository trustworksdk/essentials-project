/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.shared.interceptor;

/**
 * Each interceptor, configured on the method allows you to perform before, after or around interceptor logic according to your needs.<br>
 * The {@link Interceptor} is built around supporting Intercepting specific Operations, where an Operation is determined
 * by the user of the Interceptor. In the case of the EventStore examples of Operations are: AppendToStream, LoadLastPersistedEventRelatedTo,
 * LoadEvent, FetchStream, etc.
 * <p>
 * <b>Recommendation:</b><br>
 * Your concrete {@link Interceptor} should provide a method for each Concrete Operation following this pattern:
 * <pre>{@code
 * default <RESULT> intercept(ConcreteOperation operation, InterceptorChain<ConcreteOperation, RESULT> interceptorChain) {
 *     return interceptorChain.proceed();
 * }
 * }</pre>
 * which allows concrete Interceptors to only override the Operations that it's concerned with
 * <p>
 * Example of using the {@link InterceptorChain}:
 * <pre>{@code
 * var result = InterceptorChain.newInterceptorChainForOperation(operation,
 *                                                               allInterceptorsConfigured,
 *                                                               (interceptor, interceptorChain) -> interceptor.intercept(interceptorChain.operation(), interceptorChain),
 *                                                               () -> performDefaultBehaviorForOperation(operation))
 *                              .proceed();
 * }</pre>
 * <p>
 * <b>Changing what an operation carries</b><br>
 * An interceptor may change the input of the operation it intercepts - enrich a payload, decrypt it on the way in,
 * encrypt it on the way out, rewrite metadata - and every interceptor family in Essentials follows the same contract:
 * <ol>
 *     <li><b>Change the input in place, then proceed.</b> Call the operation's setter (e.g. {@code setMessage(...)})
 *     before {@code proceed()}. The values an operation carries are immutable: build a new one
 *     (e.g. {@code message.withPayload(...)}), never mutate one.</li>
 *     <li><b>Change the output by returning something else.</b> Read-side transformation (decrypting what was
 *     loaded) is done on the value {@code proceed()} returned.</li>
 *     <li><b>Everything downstream reads the operation.</b> Later interceptors receive the same operation object,
 *     and the default behaviour reads it after the chain reached it. A chain built in Essentials never hands the
 *     default behaviour a value captured before the chain ran. Code that builds its own chain must do the same -
 *     read the operation in the default behaviour, as {@code performDefaultBehaviorForOperation(operation)} does
 *     above - for replacement to work.</li>
 *     <li><b>Only what reaches the target has to fit it.</b> Where the target was chosen before the chain ran (a
 *     message-handler method, a command handler), interceptors may replace the payload with a value of any type, as
 *     long as the value that reaches the target is accepted by it; otherwise the invocation fails with a
 *     {@link ClassCastException}. The target is never re-resolved.</li>
 *     <li><b>Order is expressed with {@link InterceptorOrder}.</b> A decrypting interceptor must run before any
 *     interceptor that reads the payload; an encrypting one after any that enriches it.</li>
 * </ol>
 * Outer interceptors that read the operation after {@code proceed()} returned see the last replacement an inner
 * interceptor made.
 *
 * @see InterceptorChain
 */
public interface Interceptor {

}
