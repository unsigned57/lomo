package com.lomo.domain.testutil

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun invokeSuspendViaReflection(
    method: Method,
    receiver: Any,
    vararg args: Any?,
): Any? =
    suspendCancellableCoroutine { continuation ->
        val resumed = AtomicBoolean(false)

        val callback =
            object : Continuation<Any?> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<Any?>) {
                    if (!resumed.compareAndSet(false, true)) return
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) },
                    )
                }
            }

        val invocationArgs = arrayOfNulls<Any?>(args.size + 1)
        args.copyInto(invocationArgs)
        invocationArgs[args.size] = callback

        try {
            val returnValue = method.invoke(receiver, *invocationArgs)
            if (returnValue !== COROUTINE_SUSPENDED && resumed.compareAndSet(false, true)) {
                continuation.resume(returnValue)
            }
        } catch (e: InvocationTargetException) {
            if (resumed.compareAndSet(false, true)) {
                continuation.resumeWithException(e.targetException ?: e)
            }
        } catch (e: Throwable) {
            if (resumed.compareAndSet(false, true)) {
                continuation.resumeWithException(e)
            }
        }
    }
