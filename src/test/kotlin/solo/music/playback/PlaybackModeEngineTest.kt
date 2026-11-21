package solo.music.playback

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaybackModeEngineTest {
  private fun next(
    size: Int,
    current: Int,
    shuffle: Boolean = false,
    repeat: Int = DesktopPlayer.SEQUENTIAL,
    played: Set<Int> = emptySet(),
    automatic: Boolean = false,
    random: Random = Random(1),
  ) = PlaybackModeEngine.next(size, current, shuffle, repeat, played, automatic, random)

  @Test fun sequentialAdvancesThenStopsAtQueueEnd() {
    assertEquals(PlaybackModeEngine.Decision.Play(1, emptySet()), next(3, 0))
    assertEquals(PlaybackModeEngine.Decision.Exhausted, next(3, 2, automatic = true))
  }

  @Test fun repeatOneRestartsOnlyAtNaturalEnd() {
    assertEquals(PlaybackModeEngine.Decision.ReplayCurrent, next(3, 1, repeat = DesktopPlayer.REPEAT_ONE, automatic = true))
    assertEquals(PlaybackModeEngine.Decision.Play(2, emptySet()), next(3, 1, repeat = DesktopPlayer.REPEAT_ONE))
  }

  @Test fun repeatAllWrapsAfterLastTrack() {
    assertEquals(PlaybackModeEngine.Decision.Play(0, emptySet()), next(3, 2, repeat = DesktopPlayer.REPEAT_ALL, automatic = true))
  }

  @Test fun shuffleVisitsEveryTrackBeforeRepeating() {
    val random = Random(14)
    val sequence = mutableListOf(0)
    var history = setOf(0)
    repeat(3) {
      val decision = next(4, sequence.last(), shuffle = true, played = history, random = random)
      assertTrue(decision is PlaybackModeEngine.Decision.Play)
      sequence += decision.index
      history = decision.played
    }
    assertEquals(4, sequence.toSet().size)
    assertEquals(PlaybackModeEngine.Decision.Exhausted, next(4, sequence.last(), shuffle = true, played = history, automatic = true))
  }

  @Test fun shuffleRepeatStartsFreshCycleWithoutImmediateRepeat() {
    val random = Random(4)
    val history = setOf(0, 1, 2)
    val decision = next(3, 2, shuffle = true, repeat = DesktopPlayer.REPEAT_ALL, played = history, automatic = true, random = random)
    assertTrue(decision is PlaybackModeEngine.Decision.Play)
    assertNotEquals(2, decision.index)
    assertEquals(setOf(2, decision.index), decision.played)
  }
}
