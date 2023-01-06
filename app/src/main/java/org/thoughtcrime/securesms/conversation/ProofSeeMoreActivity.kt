package org.thoughtcrime.securesms.conversation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.media.AudioManagerCompat
import androidx.window.layout.WindowMetricsCalculator
import com.bumptech.glide.Glide
import com.bumptech.glide.MemoryCategory
import org.signal.core.util.dp
import org.signal.core.util.sp
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.voice.VoiceNoteMediaController
import org.thoughtcrime.securesms.components.voice.VoiceNoteMediaControllerOwner
import org.thoughtcrime.securesms.stories.StoryViewerArgs
import org.thoughtcrime.securesms.stories.viewer.page.StoryViewStateCache
import org.thoughtcrime.securesms.stories.viewer.page.StoryViewStateViewModel
import org.thoughtcrime.securesms.util.FullscreenHelper
import org.thoughtcrime.securesms.util.ServiceUtil
import org.thoughtcrime.securesms.util.ViewUtil
import kotlin.math.max
import kotlin.math.min

class ProofSeeMoreActivity : AppCompatActivity()  {


  override fun onCreate(savedInstanceState: Bundle?) {
    if (savedInstanceState != null) {

    }

    actionBar?.title = "SOME TEXT"

    super.onCreate(savedInstanceState)
    setContentView(R.layout.proof_mode_see_more)

  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
//    outState.putParcelable(DATA_CACHE, storyViewStateViewModel.storyViewStateCache)
  }

  companion object {
    private const val ARGS = "story.viewer.args"
    private const val DATA_CACHE = "story.viewer.cache"

    @JvmStatic
    fun createIntent(
      context: Context,
//      storyViewerArgs: StoryViewerArgs
    ): Intent {
      return Intent(context, ProofSeeMoreActivity::class.java)
    }
  }
}
