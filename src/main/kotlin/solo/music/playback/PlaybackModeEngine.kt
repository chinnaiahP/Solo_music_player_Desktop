package solo.music.playback

import java.util.Random

/** Queue-mode transitions kept independent from JavaFX so playback policy is testable. */
internal object PlaybackModeEngine {
  sealed interface Decision {
    data class Play(val index: Int, val played: Set<Int>) : Decision
    data object ReplayCurrent : Decision
    data object Exhausted : Decision
  }

  fun next(
    size: Int,
    current: Int,
    shuffle: Boolean,
    repeatMode: Int,
    previouslyPlayed: Set<Int>,
    automatic: Boolean,
    random: Random,
  ): Decision {
    if (size <= 0 || current !in 0 until size) return Decision.Exhausted
    if (automatic && repeatMode == DesktopPlayer.REPEAT_ONE) return Decision.ReplayCurrent

    if (!shuffle) {
      return when {
        current < size - 1 -> Decision.Play(current + 1, emptySet())
        repeatMode == DesktopPlayer.REPEAT_ALL -> Decision.Play(0, emptySet())
        else -> Decision.Exhausted
      }
    }

    var played = previouslyPlayed + current
    var candidates = (0 until size).filterNot(played::contains)
    if (candidates.isEmpty()) {
      if (repeatMode != DesktopPlayer.REPEAT_ALL) return Decision.Exhausted
      if (size == 1) return Decision.ReplayCurrent
      played = setOf(current)
      candidates = (0 until size).filter { it != current }
    }
    val next = candidates[random.nextInt(candidates.size)]
    return Decision.Play(next, played + next)
  }
}
