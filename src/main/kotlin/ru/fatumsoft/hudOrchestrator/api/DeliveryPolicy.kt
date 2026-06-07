package ru.fatumsoft.hudOrchestrator.api

enum class DeliveryPolicy {
    ENQUEUE,
    PREEMPT,
    COALESCE,
    DROP_IF_BUSY
}
