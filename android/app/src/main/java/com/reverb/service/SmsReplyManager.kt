package com.reverb.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.app.RemoteInput

/**
 * RemoteInput 기반 답장 발송.
 * 원본 알림의 Reply Action을 conversationId 키로 캐싱 → 웹 UI에서 답장 요청 시 발화.
 * RCS, WhatsApp, 일반 SMS 등 앱의 RemoteInput을 그대로 활용하므로 폭넓게 호환.
 */
object SmsReplyManager {
    private const val MAX_CACHE = 50

    // conversationId → Notification.Action (Reply 액션)
    private val actionCache = LinkedHashMap<String, Notification.Action>(MAX_CACHE, 0.75f, true)

    /**
     * NotificationService에서 알림 수신 시 Reply Action을 캐싱.
     * @param conversationId 전화번호 or 스레드 ID
     * @param replyAction Reply 버튼에 해당하는 Action
     */
    fun cacheReplyAction(conversationId: String, replyAction: Notification.Action) {
        if (actionCache.size >= MAX_CACHE) {
            val oldest = actionCache.keys.first()
            actionCache.remove(oldest)
        }
        actionCache[conversationId] = replyAction
    }

    /**
     * 웹 UI에서 답장 요청 시 호출.
     * @return Result.success(Unit) or Result.failure(exception)
     */
    fun sendReply(context: Context, conversationId: String, body: String): Result<Unit> {
        val action = actionCache[conversationId]
            ?: return Result.failure(IllegalStateException("No cached reply action for conversationId: $conversationId"))

        return try {
            val remoteInputs = action.remoteInputs
            if (remoteInputs.isNullOrEmpty()) {
                // RemoteInput 없음 → 직접 SMS 발송 (fallback)
                sendSmsDirect(conversationId, body)
            } else {
                val remoteInput = remoteInputs[0]
                val replyIntent = Intent().apply {
                    // RemoteInput에 텍스트 주입
                    val bundle = Bundle()
                    bundle.putCharSequence(remoteInput.resultKey, body)
                    RemoteInput.addResultsToIntent(remoteInputs, this, bundle)
                }
                action.actionIntent.send(context, 0, replyIntent)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sendSmsDirect(phoneNumber: String, body: String): Result<Unit> {
        return try {
            val smsManager = android.telephony.SmsManager.getDefault()
            val parts = smsManager.divideMessage(body)
            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, body, null, null)
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
