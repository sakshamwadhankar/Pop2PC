package dev.unifieddesktop.app

/**
 * Phone-first resource policy. This file deliberately has no Android dependencies so the
 * thresholds and ordering can be tested on the JVM. The service supplies the Android
 * measurements and performs the side effects.
 */
enum class Phase10GovernorStage {
    NORMAL,
    HIDDEN_STOPPED,
    FPS_30,
    FPS_15,
    LOW_RESOLUTION,
    WARNED,
}

sealed class Phase10GovernorAction {
    data object None : Phase10GovernorAction()
    data object StopHiddenWindows : Phase10GovernorAction()
    data class SetFps(val fps: Int) : Phase10GovernorAction()
    data object LowerResolution : Phase10GovernorAction()
    data object WarnPc : Phase10GovernorAction()
    /** Audit A-21: undo the D22 low-power step (full-size encoder rebuild + keyframe). */
    data object RestoreResolution : Phase10GovernorAction()
    /** Audit A-21: let minimized windows render again after a memory-pressure stop. */
    data object ResumeHiddenWindows : Phase10GovernorAction()
}

data class Phase10GovernorInput(
    val lowMemory: Boolean,
    val trimLevel: Int,
    val memAvailableKb: Long,
    val thermalStatus: Int,
    val desktopVisible: Boolean,
    /** Audit A-21: the plan fps the recovery ladder restores to (QualityPref). */
    val planFps: Int = 60,
)

/**
 * B-127 decision (manager 2026-11-07): the D22 debug thermal injection, pure so it is
 * unit-tested. `parse` turns the `p10:thermal N` harness argument into the value to
 * inject - but ONLY on a debug build; release builds ignore the action entirely (null).
 * `effectiveThermal` is the one line the governor's sample loop uses: the override wins
 * when set, otherwise the real PowerManager signal flows exactly as shipped.
 */
object ThermalInjection {
    /** @return 0..5 (0 = clear back to the real signal), or null = ignore the action. */
    fun parse(arg: String, isDebugBuild: Boolean): Int? {
        if (!isDebugBuild) return null
        val n = arg.trim().removePrefix("thermal").trim().toIntOrNull() ?: return null
        return if (n in 0..5) n else null
    }

    fun effectiveThermal(pmStatus: Int, override: Int?): Int = override ?: pmStatus
}

data class Phase10GovernorDecision(
    val previous: Phase10GovernorStage,
    val next: Phase10GovernorStage,
    val action: Phase10GovernorAction,
    val trigger: String,
)

object Phase10Governor {
    /** Android's TRIM_MEMORY_RUNNING_LOW. Kept numeric to keep this policy pure. */
    const val TRIM_MEMORY_RUNNING_LOW = 10
    /** Android's THERMAL_STATUS_MODERATE. Kept numeric to keep this policy pure. */
    const val THERMAL_STATUS_MODERATE = 2
    const val MIN_AVAILABLE_KB = 256L * 1024L
    /**
     * Audit A-21: consecutive healthy samples (2 s each) before the ladder walks back one
     * rung. 5 samples = 10 s of calm, so a passing thermal blip does not flap the encoder.
     */
    const val RECOVERY_STREAK = 5

    fun pressure(input: Phase10GovernorInput): String? {
        if (input.lowMemory) return "lowMemory=true"
        if (input.trimLevel >= TRIM_MEMORY_RUNNING_LOW) return "trim=${input.trimLevel}"
        if (input.memAvailableKb in 0 until MIN_AVAILABLE_KB) {
            return "memAvailableKb=${input.memAvailableKb}"
        }
        if (input.thermalStatus >= THERMAL_STATUS_MODERATE) {
            return "thermal=${input.thermalStatus}"
        }
        return null
    }

    fun next(
        stage: Phase10GovernorStage,
        input: Phase10GovernorInput,
    ): Phase10GovernorDecision {
        val trigger = pressure(input)
        if (trigger == null) {
            return Phase10GovernorDecision(stage, stage, Phase10GovernorAction.None, "healthy")
        }
        return when (stage) {
            Phase10GovernorStage.NORMAL -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.HIDDEN_STOPPED,
                Phase10GovernorAction.StopHiddenWindows,
                trigger,
            )
            Phase10GovernorStage.HIDDEN_STOPPED -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.FPS_30,
                Phase10GovernorAction.SetFps(30),
                trigger,
            )
            Phase10GovernorStage.FPS_30 -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.FPS_15,
                Phase10GovernorAction.SetFps(15),
                trigger,
            )
            Phase10GovernorStage.FPS_15 -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.LOW_RESOLUTION,
                Phase10GovernorAction.LowerResolution,
                trigger,
            )
            Phase10GovernorStage.LOW_RESOLUTION -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.WARNED,
                Phase10GovernorAction.WarnPc,
                trigger,
            )
            Phase10GovernorStage.WARNED -> Phase10GovernorDecision(
                stage,
                stage,
                Phase10GovernorAction.None,
                trigger,
            )
        }
    }

    /**
     * Audit A-21: the ladder used to stop at WARNED forever, so a session that had one hot
     * spell stayed at 15 fps / 3/4 encoder size for the rest of the session. After
     * [RECOVERY_STREAK] consecutive healthy samples the service calls this to undo exactly
     * one rung; full quality returns over five calm samples (about 10 s), never in one jump.
     * Null at NORMAL (nothing to undo).
     */
    fun recover(stage: Phase10GovernorStage, planFps: Int): Phase10GovernorDecision? =
        when (stage) {
            Phase10GovernorStage.WARNED -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.LOW_RESOLUTION,
                Phase10GovernorAction.RestoreResolution,
                "recovery",
            )
            Phase10GovernorStage.LOW_RESOLUTION -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.FPS_15,
                Phase10GovernorAction.None,
                "recovery",
            )
            Phase10GovernorStage.FPS_15 -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.FPS_30,
                Phase10GovernorAction.SetFps(30),
                "recovery",
            )
            Phase10GovernorStage.FPS_30 -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.HIDDEN_STOPPED,
                Phase10GovernorAction.SetFps(planFps.coerceIn(15, 60)),
                "recovery",
            )
            Phase10GovernorStage.HIDDEN_STOPPED -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.NORMAL,
                Phase10GovernorAction.ResumeHiddenWindows,
                "recovery",
            )
            Phase10GovernorStage.NORMAL -> null
        }
}
