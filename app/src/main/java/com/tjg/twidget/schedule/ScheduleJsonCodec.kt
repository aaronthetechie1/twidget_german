package com.tjg.twidget.schedule

import com.tjg.twidget.core.JsonValue
import com.tjg.twidget.core.JsonText
import com.tjg.twidget.core.json
import com.tjg.twidget.core.jsonObject

internal object ScheduleJsonCodec {
    fun encode(post: ScheduledPost): String = JsonText.stringify(post.toJsonValue())

    fun decode(raw: String): ScheduledPost = postFromValue(JsonText.parse(raw).asObject())

    fun encodeList(posts: List<ScheduledPost>): String =
        JsonText.stringify(JsonValue.ArrayValue(posts.map { it.toJsonValue() }))

    fun decodeList(raw: String): List<ScheduledPost> =
        JsonText.parse(raw).asArray().values.mapNotNull { value ->
            runCatching { postFromValue(value.asObject()) }.getOrNull()
        }

    private fun ScheduledPost.toJsonValue() = jsonObject(
        "id" to json(id),
        "provider" to json(provider.name),
        "status" to json(status.name),
        // Older betas used PUBLISHED for both presumed and confirmed posts.
        "bufferPublicationConfirmed" to json(provider == ScheduleProvider.BUFFER && status == ScheduleStatus.PUBLISHED),
        "accountId" to json(accountId),
        "accountUsername" to json(accountUsername),
        "scheduledAt" to json(scheduledAt),
        "thread" to JsonValue.ArrayValue(thread.map { it.toJsonValue() }),
        "remotePostId" to json(remotePostId),
        "remoteSubmissionId" to json(remoteSubmissionId),
        "errorMessage" to json(errorMessage),
        "createdAt" to json(createdAt),
        "updatedAt" to json(updatedAt),
        "publishedAt" to json(publishedAt),
        "pinned" to json(pinned),
        "deletedAt" to json(deletedAt),
    )

    private fun ScheduleThreadItem.toJsonValue() = jsonObject(
        "id" to json(id),
        "text" to json(text),
        "media" to JsonValue.ArrayValue(media.map { it.toJsonValue() }),
    )

    private fun ScheduleMediaSource.toJsonValue(): JsonValue.ObjectValue = when (this) {
        is LocalUriMedia -> jsonObject(
            "type" to json("local_uri"),
            "uri" to json(uri),
            "displayName" to json(displayName),
            "mimeType" to json(mimeType),
        )
        is PublicUrlMedia -> jsonObject(
            "type" to json("public_url"),
            "url" to json(url),
            "mimeType" to json(mimeType),
            "previewUrl" to json(previewUrl),
        )
    }

    private fun postFromValue(value: JsonValue.ObjectValue): ScheduledPost {
        val createdAt = value.long("createdAt")
        val provider = when (value.string("provider")) {
            "POSTPONE" -> ScheduleProvider.LOCAL_REMINDER
            else -> enumValueOf<ScheduleProvider>(value.string("provider"))
        }
        val savedStatus = enumValueOf<ScheduleStatus>(value.string("status"))
        val needsLegacyConfirmation = provider == ScheduleProvider.BUFFER &&
            savedStatus == ScheduleStatus.PUBLISHED &&
            !value.optionalString("remotePostId").isNullOrBlank() &&
            !value.optionalBoolean("bufferPublicationConfirmed")
        return ScheduledPost(
            id = value.string("id"),
            provider = provider,
            status = if (needsLegacyConfirmation) ScheduleStatus.AWAITING_CONFIRMATION else savedStatus,
            accountId = value.optionalString("accountId"),
            accountUsername = value.string("accountUsername"),
            scheduledAt = value.optionalLong("scheduledAt"),
            thread = value.array("thread").values.map { threadFromValue(it.asObject()) },
            remotePostId = value.optionalString("remotePostId"),
            remoteSubmissionId = value.optionalString("remoteSubmissionId"),
            errorMessage = value.optionalString("errorMessage"),
            createdAt = createdAt,
            updatedAt = value.long("updatedAt"),
            publishedAt = if (needsLegacyConfirmation) null else value.optionalLong("publishedAt"),
            pinned = value.optionalBoolean("pinned"),
            deletedAt = value.optionalLong("deletedAt"),
        )
    }

    private fun threadFromValue(value: JsonValue.ObjectValue) = ScheduleThreadItem(
        id = value.string("id"),
        text = value.string("text"),
        media = value.array("media").values.map { mediaFromValue(it.asObject()) },
    )

    private fun mediaFromValue(value: JsonValue.ObjectValue): ScheduleMediaSource = when (value.string("type")) {
        "local_uri" -> LocalUriMedia(
            uri = value.string("uri"),
            displayName = value.optionalString("displayName"),
            mimeType = value.optionalString("mimeType"),
        )
        "public_url" -> PublicUrlMedia(
            url = value.string("url"),
            mimeType = value.optionalString("mimeType"),
            previewUrl = value.optionalString("previewUrl"),
        )
        "postpone_library" -> PublicUrlMedia(
            url = value.optionalString("url").orEmpty(),
            mimeType = value.optionalString("mimeType"),
        )
        else -> error("Unknown media source type")
    }
}
