package com.example.actions

/**
 * Stage 5 contract for executing offline Torch, Call, and Alarm actions with fallback chains.
 */
enum class ActionExecutionPath {
    DIRECT_API,
    FALLBACK_INTENT_OR_NOTIFICATION,
    FAILED
}
