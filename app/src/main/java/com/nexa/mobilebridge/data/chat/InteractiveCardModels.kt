package com.nexa.mobilebridge.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class CardField(
    val key: String,
    val label: String,
    val value: String,
    val tone: String = "NEUTRAL" // NEUTRAL | PRIMARY | EMERALD | AMBER | CRIMSON
)

data class CardDiff(
    val oldValue: String,
    val newValue: String
)

data class CardSelectableItem(
    val id: String,
    val label: String,
    val meta: String,
    val selected: Boolean = true
)

data class CardMetricItem(
    val label: String,
    val value: String,
    val tone: String = "NEUTRAL", // NEUTRAL | PRIMARY | EMERALD | AMBER | CRIMSON
    val progress: Int? = null     // Optional 0..100 progress bar (e.g. Budget Recap)
)

data class CardActionItem(
    val id: String,
    val label: String,
    val style: String = "SECONDARY", // PRIMARY | SECONDARY | DESTRUCTIVE
    val kind: String = "",           // "" | PICK_CATEGORY | EDIT_FINANCE | EDIT_VAULT | REJECT_WITH_REASON | MULTI_SELECT_SUBMIT | SEND_PROMPT
    val prompt: String = ""          // Optional prompt text for SEND_PROMPT quick follow-up chips
)

data class InteractiveCardData(
    val version: Int = 1,
    val cardId: String,
    val cardType: String,            // FINANCE_TX | RECURRING_BILL | IDENTITY_PROPOSAL | MEMORY_HYGIENE | DISCIPLINE_CHECK | CALENDAR_CONFLICT | FINANCE_DELETE | TASK_CONFIRM | PROACTIVE_TASKS | VAULT_METADATA | BRIEFING_SUMMARY | BUDGET_RECAP
    val presentation: String = "CARD", // CARD | CHIPS | INLINE_CARD | SUMMARY_CARD
    val status: String = "PENDING",  // PENDING | RESOLVED | EXPIRED | INFO
    val title: String,
    val subtitle: String = "",
    val badge: String = "",
    val accent: String = "PRIMARY",  // PRIMARY | EMERALD | AMBER | CRIMSON
    val heroValue: String = "",
    val heroTone: String = "NEUTRAL", // EXPENSE | INCOME | TRANSFER | NEUTRAL
    val expiresAtMs: Long = 0L,
    val expiresLabel: String = "",
    val promptText: String = "",
    val confidence: Int? = null,
    val diff: CardDiff? = null,
    val fields: List<CardField> = emptyList(),
    val metrics: List<CardMetricItem> = emptyList(),
    val categories: List<String> = emptyList(),
    val accounts: List<String> = emptyList(),
    val paymentMethods: List<String> = emptyList(),
    val selectableItems: List<CardSelectableItem> = emptyList(),
    val actions: List<CardActionItem> = emptyList(),
    val metaJson: String = "{}",
    val resolvedBadge: String = "",
    val resolvedDetail: String = ""
) {
    val isPending: Boolean get() = status.equals("PENDING", ignoreCase = true)
    val isResolved: Boolean get() = status.equals("RESOLVED", ignoreCase = true)
    val isExpired: Boolean get() = status.equals("EXPIRED", ignoreCase = true)

    fun metaString(key: String, default: String = ""): String {
        return InteractiveCardCodec.readMetaString(metaJson, key, default)
    }

    fun metaLong(key: String, default: Long = 0L): Long {
        return InteractiveCardCodec.readMetaLong(metaJson, key, default)
    }
}

object InteractiveCardCodec {
    private const val META_TYPE = "interactive_card"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun readMetaString(metaJson: String, key: String, default: String = ""): String {
        return try {
            val obj = json.parseToJsonElement(metaJson).jsonObject
            obj[key]?.jsonPrimitive?.contentOrNull ?: default
        } catch (_: Exception) {
            default
        }
    }

    fun readMetaLong(metaJson: String, key: String, default: Long = 0L): Long {
        return try {
            val obj = json.parseToJsonElement(metaJson).jsonObject
            val prim = obj[key]?.jsonPrimitive ?: return default
            prim.longOrNull ?: prim.contentOrNull?.toLongOrNull() ?: default
        } catch (_: Exception) {
            default
        }
    }

    fun parseFromExecutionMetadata(raw: String?): InteractiveCardData? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            if (root.str("type") != META_TYPE) return null
            val cardObj = root["card"] as? JsonObject ?: return null
            parseFromJsonObject(cardObj)
        } catch (_: Exception) {
            null
        }
    }

    fun parseFromJsonString(rawJson: String?): InteractiveCardData? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            val obj = json.parseToJsonElement(rawJson).jsonObject
            parseFromJsonObject(obj)
        } catch (_: Exception) {
            null
        }
    }

    fun toExecutionMetadataJson(card: InteractiveCardData): String {
        return buildJsonObject {
            put("type", META_TYPE)
            put("card_id", card.cardId)
            put("card", toJsonObject(card))
        }.toString()
    }

    fun parseFromJsonObject(obj: JsonObject): InteractiveCardData? {
        val cardId = obj.str("card_id").trim()
        val cardType = obj.str("card_type").trim().uppercase()
        val title = obj.str("title").trim()
        if (cardId.isEmpty() || cardType.isEmpty() || title.isEmpty()) return null

        val fields = (obj["fields"] as? JsonArray)?.mapIndexedNotNull { idx, elem ->
            val f = elem as? JsonObject ?: return@mapIndexedNotNull null
            val label = f.str("label").trim()
            val value = f.str("value").trim()
            if (label.isEmpty() && value.isEmpty()) null
            else CardField(
                key = f.str("key", "f_$idx"),
                label = label,
                value = value,
                tone = f.str("tone", "NEUTRAL").uppercase()
            )
        } ?: emptyList()

        val metrics = (obj["metrics"] as? JsonArray)?.mapNotNull { elem ->
            val m = elem as? JsonObject ?: return@mapNotNull null
            val label = m.str("label").trim()
            val value = m.str("value").trim()
            if (label.isEmpty() && value.isEmpty()) null
            else CardMetricItem(
                label = label,
                value = value,
                tone = m.str("tone", "NEUTRAL").uppercase(),
                progress = (m["progress"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 100)
            )
        } ?: emptyList()

        val categories = (obj["categories"] as? JsonArray)?.mapNotNull { elem ->
            (elem as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        } ?: emptyList()

        val accounts = (obj["accounts"] as? JsonArray)?.mapNotNull { elem ->
            (elem as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        } ?: emptyList()

        val paymentMethods = (obj["payment_methods"] as? JsonArray)?.mapNotNull { elem ->
            (elem as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        } ?: emptyList()

        val selectableItems = (obj["selectable_items"] as? JsonArray)?.mapNotNull { elem ->
            val item = elem as? JsonObject ?: return@mapNotNull null
            val id = item.str("id").trim()
            val label = item.str("label").trim()
            if (id.isEmpty() || label.isEmpty()) null
            else CardSelectableItem(
                id = id,
                label = label,
                meta = item.str("meta").trim(),
                selected = item.bool("selected", true)
            )
        } ?: emptyList()

        val actions = (obj["actions"] as? JsonArray)?.mapNotNull { elem ->
            val act = elem as? JsonObject ?: return@mapNotNull null
            val id = act.str("id").trim()
            val label = act.str("label").trim()
            if (id.isEmpty() || label.isEmpty()) null
            else CardActionItem(
                id = id,
                label = label,
                style = act.str("style", "SECONDARY").uppercase(),
                kind = act.str("kind", "").uppercase(),
                prompt = act.str("prompt").trim()
            )
        } ?: emptyList()

        val diffObj = obj["diff"] as? JsonObject
        val diff = diffObj?.let {
            CardDiff(
                oldValue = it.str("old_value").trim(),
                newValue = it.str("new_value").trim()
            )
        }

        val confidence = (obj["confidence"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..100 }
        val metaObj = obj["meta"] as? JsonObject

        return InteractiveCardData(
            version = obj.int("version", 1),
            cardId = cardId,
            cardType = cardType,
            presentation = obj.str("presentation", "CARD").ifBlank { "CARD" }.uppercase(),
            status = obj.str("status", "PENDING").uppercase(),
            title = title,
            subtitle = obj.str("subtitle").trim(),
            badge = obj.str("badge").trim(),
            accent = obj.str("accent", "PRIMARY").uppercase(),
            heroValue = obj.str("hero_value").trim(),
            heroTone = obj.str("hero_tone", "NEUTRAL").uppercase(),
            expiresAtMs = obj.long("expires_at", 0L),
            expiresLabel = obj.str("expires_label").trim(),
            promptText = obj.str("prompt_text").trim(),
            confidence = confidence,
            diff = diff,
            fields = fields,
            metrics = metrics,
            categories = categories,
            accounts = accounts,
            paymentMethods = paymentMethods,
            selectableItems = selectableItems,
            actions = actions,
            metaJson = metaObj?.toString() ?: "{}",
            resolvedBadge = obj.str("resolved_badge").trim(),
            resolvedDetail = obj.str("resolved_detail").trim()
        )
    }

    fun toJsonObject(card: InteractiveCardData): JsonObject {
        val parsedMeta: JsonElement = try {
            json.parseToJsonElement(card.metaJson)
        } catch (_: Exception) {
            buildJsonObject {}
        }
        return buildJsonObject {
            put("version", card.version)
            put("card_id", card.cardId)
            put("card_type", card.cardType)
            put("presentation", card.presentation)
            put("status", card.status)
            put("title", card.title)
            if (card.subtitle.isNotEmpty()) put("subtitle", card.subtitle)
            if (card.badge.isNotEmpty()) put("badge", card.badge)
            put("accent", card.accent)
            if (card.heroValue.isNotEmpty()) put("hero_value", card.heroValue)
            put("hero_tone", card.heroTone)
            if (card.expiresAtMs > 0L) put("expires_at", card.expiresAtMs)
            if (card.expiresLabel.isNotEmpty()) put("expires_label", card.expiresLabel)
            if (card.promptText.isNotEmpty()) put("prompt_text", card.promptText)
            if (card.confidence != null) put("confidence", card.confidence)
            if (card.diff != null) {
                put("diff", buildJsonObject {
                    put("old_value", card.diff.oldValue)
                    put("new_value", card.diff.newValue)
                })
            }
            put("fields", buildJsonArray {
                card.fields.forEach { f ->
                    add(buildJsonObject {
                        put("key", f.key)
                        put("label", f.label)
                        put("value", f.value)
                        if (f.tone != "NEUTRAL") put("tone", f.tone)
                    })
                }
            })
            if (card.metrics.isNotEmpty()) {
                put("metrics", buildJsonArray {
                    card.metrics.forEach { m ->
                        add(buildJsonObject {
                            put("label", m.label)
                            put("value", m.value)
                            put("tone", m.tone)
                            if (m.progress != null) put("progress", m.progress)
                        })
                    }
                })
            }
            if (card.categories.isNotEmpty()) {
                put("categories", buildJsonArray {
                    card.categories.forEach { add(JsonPrimitive(it)) }
                })
            }
            if (card.accounts.isNotEmpty()) {
                put("accounts", buildJsonArray {
                    card.accounts.forEach { add(JsonPrimitive(it)) }
                })
            }
            if (card.paymentMethods.isNotEmpty()) {
                put("payment_methods", buildJsonArray {
                    card.paymentMethods.forEach { add(JsonPrimitive(it)) }
                })
            }
            if (card.selectableItems.isNotEmpty()) {
                put("selectable_items", buildJsonArray {
                    card.selectableItems.forEach { item ->
                        add(buildJsonObject {
                            put("id", item.id)
                            put("label", item.label)
                            put("meta", item.meta)
                            put("selected", item.selected)
                        })
                    }
                })
            }
            put("actions", buildJsonArray {
                card.actions.forEach { act ->
                    add(buildJsonObject {
                        put("id", act.id)
                        put("label", act.label)
                        put("style", act.style)
                        if (act.kind.isNotEmpty()) put("kind", act.kind)
                        if (act.prompt.isNotEmpty()) put("prompt", act.prompt)
                    })
                }
            })
            put("meta", parsedMeta)
            if (card.resolvedBadge.isNotEmpty()) put("resolved_badge", card.resolvedBadge)
            if (card.resolvedDetail.isNotEmpty()) put("resolved_detail", card.resolvedDetail)
        }
    }

    fun applyCardUpdateJson(existing: InteractiveCardData, updateJson: String): InteractiveCardData {
        val updateObj = try {
            json.parseToJsonElement(updateJson).jsonObject
        } catch (_: Exception) {
            return existing
        }
        val updatedCardObj = updateObj["updated_card"] as? JsonObject
        val base = if (updatedCardObj != null) {
            parseFromJsonObject(updatedCardObj) ?: existing
        } else {
            existing
        }
        val nextStatus = updateObj.str("status", base.status).ifBlank { base.status }.uppercase()
        val nextBadge = updateObj.str("resolved_badge", base.resolvedBadge)
        val nextDetail = updateObj.str("resolved_detail", base.resolvedDetail)
        return base.copy(
            status = nextStatus,
            resolvedBadge = nextBadge,
            resolvedDetail = nextDetail
        )
    }

    private fun JsonObject.str(key: String, default: String = ""): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: default

    private fun JsonObject.int(key: String, default: Int = 0): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: default

    private fun JsonObject.long(key: String, default: Long = 0L): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: default

    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
}
