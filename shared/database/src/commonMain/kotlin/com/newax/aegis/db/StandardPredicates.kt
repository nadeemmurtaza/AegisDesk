package com.newax.aegis.db

object StandardPredicates {
    const val KNOWS               = "knows"
    const val WORKS_AT            = "works_at"
    const val WORKED_AT           = "worked_at"
    const val LIVES_IN            = "lives_in"
    const val LIKES               = "likes"
    const val DISLIKES            = "dislikes"
    const val BIRTHDAY_ON         = "birthday_on"
    const val STUDIES_AT          = "studies_at"
    const val RELATED_TO          = "related_to"
    const val CALLED              = "called"
    const val TEXTED              = "texted"
    const val MET                 = "met"
    const val HOBBY_IS            = "hobby_is"
    const val DRIVES              = "drives"
    const val HAS_CONDITION       = "has_condition"
    const val TAKES_MEDICATION    = "takes_medication"
    const val ALLERGIC_TO         = "allergic_to"
    const val MEMBER_OF           = "member_of"
    const val PREFERRED_CHANNEL   = "preferred_channel"
    const val AVOIDS_CHANNEL      = "avoids_channel"
    const val QUIET_HOURS_START   = "quiet_hours_start"
    const val QUIET_HOURS_END     = "quiet_hours_end"
    const val RELATIONSHIP_TYPE   = "relationship_type"
    const val NICKNAME            = "nickname"
    const val PREFERRED_TONE      = "preferred_tone"
    const val ATTENDED_BY         = "attended_by"
    const val ABOUT               = "about"
    const val PARTICIPANT         = "participant"
    const val TOPIC               = "topic"
    const val OCCURRED_AT         = "occurred_at"
    const val WORKS_ON            = "works_on"
    const val HAS_ISSUE           = "has_issue"
    const val MENTIONED_IN        = "mentioned_in"
    const val EVIDENCE            = "evidence"
    const val ACTOR               = "actor"
    const val ACTION              = "action"
    const val USUAL_TIME          = "usual_time"
    const val FREQUENCY           = "frequency"
    const val SUPPORTED_BY        = "supported_by"
    const val PROFILE_POINTER     = "profile_pointer"
    const val CONTENT_POINTER     = "content_pointer"
    const val PARENT_OF           = "parent_of"
    const val CHILD_OF            = "child_of"
    const val REPORTS_TO          = "reports_to"
    const val OWNS                = "owns"

    val ALL: List<String> = listOf(
        KNOWS, WORKS_AT, WORKED_AT, LIVES_IN, LIKES, DISLIKES, BIRTHDAY_ON,
        STUDIES_AT, RELATED_TO, CALLED, TEXTED, MET, HOBBY_IS, DRIVES,
        HAS_CONDITION, TAKES_MEDICATION, ALLERGIC_TO, MEMBER_OF,
        PREFERRED_CHANNEL, AVOIDS_CHANNEL, QUIET_HOURS_START, QUIET_HOURS_END,
        RELATIONSHIP_TYPE, NICKNAME, PREFERRED_TONE, ATTENDED_BY, ABOUT,
        PARTICIPANT, TOPIC, OCCURRED_AT, WORKS_ON, HAS_ISSUE, MENTIONED_IN,
        EVIDENCE, ACTOR, ACTION, USUAL_TIME, FREQUENCY,
        SUPPORTED_BY, PROFILE_POINTER, CONTENT_POINTER, PARENT_OF, CHILD_OF,
        REPORTS_TO, OWNS
    )

    /** Predicates whose object is a named entity (not a primitive value). */
    val ENTITY_OBJECT: Set<String> = setOf(
        WORKS_AT, WORKED_AT, LIVES_IN, STUDIES_AT, MEMBER_OF, DRIVES,
        KNOWS, RELATED_TO, MET, CALLED, TEXTED, ATTENDED_BY, PARTICIPANT,
        ABOUT, WORKS_ON, HAS_ISSUE, MENTIONED_IN, EVIDENCE, ACTOR,
        PARENT_OF, CHILD_OF, REPORTS_TO, SUPPORTED_BY, OWNS
    )
}
