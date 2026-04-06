package ru.fatumsoft.hudOrchestrator.api

enum class Priority(val weight: Int) {
    DEBUG(10),
    LOW(30),
    NORMAL(50),
    HIGH(70),
    CRITICAL(90)
}
