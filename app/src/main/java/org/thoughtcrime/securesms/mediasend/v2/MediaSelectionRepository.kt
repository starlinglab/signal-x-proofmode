package org.thoughtcrime.securesms.mediasend.v2

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.BreakIteratorCompat
import org.signal.core.util.ThreadUtil
import org.signal.core.util.logging.Log
import org.signal.imageeditor.core.model.EditorModel
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.conversation.MessageSendType
import org.thoughtcrime.securesms.database.AttachmentTable.TransformProperties
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.database.model.StoryType
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.StorySend
import org.thoughtcrime.securesms.mediasend.CompositeMediaTransform
import org.thoughtcrime.securesms.mediasend.ImageEditorModelRenderMediaTransform
import org.thoughtcrime.securesms.mediasend.Media
import org.thoughtcrime.securesms.mediasend.MediaRepository
import org.thoughtcrime.securesms.mediasend.MediaSendActivityResult
import org.thoughtcrime.securesms.mediasend.MediaTransform
import org.thoughtcrime.securesms.mediasend.MediaUploadRepository
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_ENABLED
import org.thoughtcrime.securesms.mediasend.ProofModeUtil
import org.thoughtcrime.securesms.mediasend.SentMediaQualityTransform
import org.thoughtcrime.securesms.mediasend.VideoEditorFragment
import org.thoughtcrime.securesms.mediasend.VideoTrimTransform
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage
import org.thoughtcrime.securesms.mms.OutgoingSecureMediaMessage
import org.thoughtcrime.securesms.mms.SentMediaQuality
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.providers.BlobProvider
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.scribbles.ImageEditorFragment
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.sms.MessageSender.PreUploadResult
import org.thoughtcrime.securesms.stories.Stories
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.MessageUtil
import org.witness.proofmode.ProofMode
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val TAG = Log.tag(MediaSelectionRepository::class.java)

class MediaSelectionRepository(context: Context) {

  private val context: Context = context.applicationContext

  private val mediaRepository = MediaRepository()

  val uploadRepository = MediaUploadRepository(this.context)
  val isMetered: Observable<Boolean> = MeteredConnectivity.isMetered(this.context)

  fun populateAndFilterMedia(media: List<Media>, mediaConstraints: MediaConstraints, maxSelection: Int, isStory: Boolean): Single<MediaValidator.FilterResult> {
    return Single.fromCallable {
      val populatedMedia = mediaRepository.getPopulatedMedia(context, media)

      MediaValidator.filterMedia(context, populatedMedia, mediaConstraints, maxSelection, isStory)
    }.subscribeOn(Schedulers.io())
  }

  /**
   * Tries to send the selected media, performing proper transformations for edited images and videos.
   */
  fun send(
    selectedMedia: List<Media>,
    stateMap: Map<Uri, Any>,
    quality: SentMediaQuality,
    message: CharSequence?,
    isSms: Boolean,
    isViewOnce: Boolean,
    singleContact: ContactSearchKey.RecipientSearchKey?,
    contacts: List<ContactSearchKey.RecipientSearchKey>,
    mentions: List<Mention>,
    sendType: MessageSendType
  ): Maybe<MediaSendActivityResult> {
    if (isSms && contacts.isNotEmpty()) {
      throw IllegalStateException("Provided recipients to send to, but this is SMS!")
    }

    if (selectedMedia.isEmpty()) {
      throw IllegalStateException("No selected media!")
    }

    val newMediaList = arrayListOf<Media>()
    newMediaList.addAll(selectedMedia)
    if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_ENABLED, true)) {
      val file = ProofModeUtil.createZipProof(selectedMedia.first().proofHash, context)
      newMediaList.add(
        Media(
          requireNotNull(Uri.fromFile(file)),
          MediaUtil.OCTET,
          "",
          System.currentTimeMillis(),
          0,
          0,
          java.lang.String.valueOf(file.length() / 1024).toLong(),
          0,
          false,
          false,
          Optional.of(Media.ALL_MEDIA_BUCKET_ID),
          Optional.empty(),
          Optional.empty()
        )
      )
    }

    val isSendingToStories = singleContact?.isStory == true || contacts.any { it.isStory }
    val sentMediaQuality = if (isSendingToStories) SentMediaQuality.STANDARD else quality

    return Maybe.create<MediaSendActivityResult> { emitter ->
      val trimmedBody: String = if (isViewOnce) "" else getTruncatedBody(message?.toString()?.trim()) ?: ""
      val trimmedMentions: List<Mention> = if (isViewOnce) emptyList() else mentions
      val modelsToTransform: Map<Media, MediaTransform> = buildModelsToTransform(newMediaList, stateMap, sentMediaQuality)
      val oldToNewMediaMap: Map<Media, Media> = MediaRepository.transformMediaSync(context, newMediaList, modelsToTransform)
      val updatedMedia = oldToNewMediaMap.values.toList()

      for (media in updatedMedia) {
        Log.w(TAG, media.uri.toString() + " : " + media.transformProperties.map { t: TransformProperties -> "" + t.isVideoTrim }.orElse("null"))
      }

      val singleRecipient: Recipient? = singleContact?.let { Recipient.resolved(it.recipientId) }
      val storyType: StoryType = if (singleRecipient?.isDistributionList == true) {
        SignalDatabase.distributionLists.getStoryType(singleRecipient.requireDistributionListId())
      } else if (singleRecipient?.isGroup == true && singleContact.isStory) {
        StoryType.STORY_WITH_REPLIES
      } else {
        StoryType.NONE
      }

      if (isSms || MessageSender.isLocalSelfSend(context, singleRecipient, isSms)) {
        Log.i(TAG, "SMS or local self-send. Skipping pre-upload.")
        emitter.onSuccess(MediaSendActivityResult.forTraditionalSend(singleRecipient!!.id, updatedMedia, trimmedBody, sendType, isViewOnce, trimmedMentions, StoryType.NONE))
      } else {
        val splitMessage = MessageUtil.getSplitMessage(context, trimmedBody, sendType.calculateCharacters(trimmedBody).maxPrimaryMessageSize)
        val splitBody = splitMessage.body

        if (splitMessage.textSlide.isPresent) {
          val slide: Slide = splitMessage.textSlide.get()
          uploadRepository.startUpload(
            MediaBuilder.buildMedia(
              uri = requireNotNull(slide.uri),
              mimeType = slide.contentType,
              date = System.currentTimeMillis(),
              size = slide.fileSize,
              borderless = slide.isBorderless,
              videoGif = slide.isVideoGif
            ),
            singleRecipient
          )
        }

        val clippedVideosForStories: List<Media> = if (isSendingToStories) {
          updatedMedia.filter {
            Stories.MediaTransform.getSendRequirements(it) == Stories.MediaTransform.SendRequirements.REQUIRES_CLIP
          }.map { media ->
            Stories.MediaTransform.clipMediaToStoryDuration(media)
          }.flatten()
        } else emptyList()

        uploadRepository.applyMediaUpdates(oldToNewMediaMap, singleRecipient)
        uploadRepository.updateCaptions(updatedMedia)
        uploadRepository.updateDisplayOrder(updatedMedia)
        uploadRepository.getPreUploadResults { uploadResults ->
          if (contacts.isNotEmpty()) {
            sendMessages(contacts, splitBody, uploadResults, trimmedMentions, isViewOnce, clippedVideosForStories)
            uploadRepository.deleteAbandonedAttachments()
            emitter.onComplete()
          } else if (uploadResults.isNotEmpty()) {
            emitter.onSuccess(MediaSendActivityResult.forPreUpload(singleRecipient!!.id, uploadResults, splitBody, sendType, isViewOnce, trimmedMentions, storyType))
          } else {
            Log.w(TAG, "Got empty upload results! isSms: $isSms, updatedMedia.size(): ${updatedMedia.size}, isViewOnce: $isViewOnce, target: $singleContact")
            emitter.onSuccess(MediaSendActivityResult.forTraditionalSend(singleRecipient!!.id, updatedMedia, trimmedBody, sendType, isViewOnce, trimmedMentions, storyType))
          }
        }
      }
    }.subscribeOn(Schedulers.io()).cast(MediaSendActivityResult::class.java)
  }

  private fun getTruncatedBody(body: String?): String? {
    return if (!Stories.isFeatureEnabled() || body.isNullOrEmpty()) {
      body
    } else {
      val iterator = BreakIteratorCompat.getInstance()
      iterator.setText(body)
      iterator.take(Stories.MAX_CAPTION_SIZE).toString()
    }
  }

  fun deleteBlobs(media: List<Media>) {
    media
      .map(Media::getUri)
      .filter(BlobProvider::isAuthority)
      .forEach { BlobProvider.getInstance().delete(context, it) }
  }

  fun cleanUp(selectedMedia: List<Media>) {
    deleteBlobs(selectedMedia)
    uploadRepository.cancelAllUploads()
    uploadRepository.deleteAbandonedAttachments()
  }

  fun isLocalSelfSend(recipient: Recipient?, isSms: Boolean): Boolean {
    return MessageSender.isLocalSelfSend(context, recipient, isSms)
  }

  @WorkerThread
  private fun buildModelsToTransform(
    selectedMedia: List<Media>,
    stateMap: Map<Uri, Any>,
    quality: SentMediaQuality
  ): Map<Media, MediaTransform> {
    val modelsToRender: MutableMap<Media, MediaTransform> = mutableMapOf()

    selectedMedia.forEach {
      val state = stateMap[it.uri]
      if (state is ImageEditorFragment.Data) {
        val model: EditorModel? = state.readModel()
        if (model != null && model.isChanged) {
          modelsToRender[it] = ImageEditorModelRenderMediaTransform(model)
        }
      }

      if (state is VideoEditorFragment.Data && state.isDurationEdited) {
        modelsToRender[it] = VideoTrimTransform(state)
      }

      if (quality == SentMediaQuality.HIGH) {
        val existingTransform: MediaTransform? = modelsToRender[it]

        modelsToRender[it] = if (existingTransform == null) {
          SentMediaQualityTransform(quality)
        } else {
          CompositeMediaTransform(existingTransform, SentMediaQualityTransform(quality))
        }
      }
    }

    return modelsToRender
  }

  @WorkerThread
  private fun sendMessages(
    contacts: List<ContactSearchKey.RecipientSearchKey>,
    body: String,
    preUploadResults: Collection<PreUploadResult>,
    mentions: List<Mention>,
    isViewOnce: Boolean,
    storyClips: List<Media>,
  ) {
    val nonStoryMessages: MutableList<OutgoingSecureMediaMessage> = ArrayList(contacts.size)
    val storyPreUploadMessages: MutableMap<PreUploadResult, MutableList<OutgoingSecureMediaMessage>> = mutableMapOf()
    val zipPreUploadMessages: MutableMap<PreUploadResult, MutableList<OutgoingSecureMediaMessage>> = mutableMapOf()
    val storyClipMessages: MutableList<OutgoingSecureMediaMessage> = ArrayList()
    val distributionListPreUploadSentTimestamps: MutableMap<PreUploadResult, Long> = mutableMapOf()
    val distributionListStoryClipsSentTimestamps: MutableMap<MediaKey, Long> = mutableMapOf()

    for (contact in contacts) {
      val recipient = Recipient.resolved(contact.recipientId)
      val isStory = contact.isStory || recipient.isDistributionList

      if (isStory && !recipient.isMyStory) {
        SignalStore.storyValues().setLatestStorySend(StorySend.newSend(recipient))
      }

      val storyType: StoryType = when {
        recipient.isDistributionList -> SignalDatabase.distributionLists.getStoryType(recipient.requireDistributionListId())
        isStory -> StoryType.STORY_WITH_REPLIES
        else -> StoryType.NONE
      }
      val message = OutgoingMediaMessage(
        recipient,
        body,
        emptyList(),
        if (recipient.isDistributionList) distributionListPreUploadSentTimestamps.getOrPut(preUploadResults.first()) { System.currentTimeMillis() } else System.currentTimeMillis(),
        -1,
        if (isStory) 0 else TimeUnit.SECONDS.toMillis(recipient.expiresInSeconds.toLong()),
        isViewOnce,
        ThreadTable.DistributionTypes.DEFAULT,
        storyType,
        null,
        false,
        null,
        emptyList(),
        emptyList(),
        mentions,
        mutableSetOf(),
        mutableSetOf(),
        null
      )

      val emptyTextMessage = OutgoingMediaMessage(
        message.recipient,
        "",
        emptyList(),
        message.sentTimeMillis,
        -1,
        message.expiresIn,
        message.isViewOnce,
        message.distributionType,
        message.storyType,
        null,
        false,
        null,
        emptyList(),
        emptyList(),
        mentions,
        mutableSetOf(),
        mutableSetOf(),
        null
      )

      if (isStory) {
        preUploadResults.filterNot { result -> storyClips.any { it.uri == result.media.uri } }.forEach {
          val list = storyPreUploadMessages[it] ?: mutableListOf()
          list.add(
            OutgoingSecureMediaMessage(message).withSentTimestamp(
              if (recipient.isDistributionList) {
                distributionListPreUploadSentTimestamps.getOrPut(it) { System.currentTimeMillis() }
              } else {
                System.currentTimeMillis()
              }
            )
          )
          storyPreUploadMessages[it] = list

          // XXX We must do this to avoid sending out messages to the same recipient with the same
          //     sentTimestamp. If we do this, they'll be considered dupes by the receiver.
          ThreadUtil.sleep(5)
        }

        storyClips.forEach {
          storyClipMessages.add(
            OutgoingSecureMediaMessage(
              OutgoingMediaMessage(
                recipient,
                body,
                listOf(MediaUploadRepository.asAttachment(context, it)),
                if (recipient.isDistributionList) distributionListStoryClipsSentTimestamps.getOrPut(it.asKey()) { System.currentTimeMillis() } else System.currentTimeMillis(),
                -1,
                0,
                isViewOnce,
                ThreadTable.DistributionTypes.DEFAULT,
                storyType,
                null,
                false,
                null,
                emptyList(),
                emptyList(),
                mentions,
                mutableSetOf(),
                mutableSetOf(),
                null
              )
            )
          )

          // XXX We must do this to avoid sending out messages to the same recipient with the same
          //     sentTimestamp. If we do this, they'll be considered dupes by the receiver.
          ThreadUtil.sleep(5)
        }
      } else {
        var isChanged = false
        if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_ENABLED, true)) {
          preUploadResults.filterNot { result -> storyClips.any { it.uri == result.media.uri } }.forEach {
            val list = zipPreUploadMessages[it] ?: mutableListOf()
            list.add(
              OutgoingSecureMediaMessage(if (isChanged) message else emptyTextMessage).withSentTimestamp(
                if (recipient.isDistributionList) {
                  distributionListPreUploadSentTimestamps.getOrPut(it) { System.currentTimeMillis() }
                } else {
                  System.currentTimeMillis()
                }
              )
            )
            isChanged = true
            zipPreUploadMessages[it] = list

            // XXX We must do this to avoid sending out messages to the same recipient with the same
            //     sentTimestamp. If we do this, they'll be considered dupes by the receiver.
            ThreadUtil.sleep(5)
          }
          ProofModeUtil.clearLocalSettings(context)
        }
        nonStoryMessages.add(OutgoingSecureMediaMessage(message))

        // XXX We must do this to avoid sending out messages to the same recipient with the same
        //     sentTimestamp. If we do this, they'll be considered dupes by the receiver.
        ThreadUtil.sleep(5)
      }
    }

    if (nonStoryMessages.isNotEmpty()) {
      if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_ENABLED, true)) {
        zipPreUploadMessages.forEach { (preUploadResult, messages) ->
          MessageSender.sendMediaBroadcast(context, messages, Collections.singleton(preUploadResult), true)
        }
      } else {
        Log.d(TAG, "Sending ${nonStoryMessages.size} preupload messages to chats")
        MessageSender.sendMediaBroadcast(
          context,
          nonStoryMessages,
          preUploadResults,
          true
        )
      }
    }

    if (storyPreUploadMessages.isNotEmpty()) {
      Log.d(TAG, "Sending ${storyPreUploadMessages.size} preload messages to stories")
      storyPreUploadMessages.forEach { (preUploadResult, messages) ->
        MessageSender.sendMediaBroadcast(context, messages, Collections.singleton(preUploadResult), nonStoryMessages.isEmpty())
      }
    }

    if (storyClipMessages.isNotEmpty()) {
      Log.d(TAG, "Sending ${storyClipMessages.size} video clip messages to stories")
      MessageSender.sendStories(context, storyClipMessages, null, null)
    }
  }

  private fun Media.asKey(): MediaKey {
    return MediaKey(this, this.transformProperties)
  }

  data class MediaKey(val media: Media, val mediaTransform: Optional<TransformProperties>)
}
