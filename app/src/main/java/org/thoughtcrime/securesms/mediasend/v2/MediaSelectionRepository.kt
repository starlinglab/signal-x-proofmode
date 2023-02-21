package org.thoughtcrime.securesms.mediasend.v2

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.preference.PreferenceManager
import com.google.android.gms.location.LocationServices
import com.mobilecoin.lib.AccountKey
import com.mobilecoin.lib.AccountSnapshot
import com.mobilecoin.lib.DefaultRng
import com.mobilecoin.lib.Mnemonics
import com.mobilecoin.lib.MobileCoinClient
import com.mobilecoin.lib.PrintableWrapper
import com.mobilecoin.lib.network.TransportProtocol
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.signal.core.util.BreakIteratorCompat
import org.signal.core.util.ThreadUtil
import org.signal.core.util.logging.Log
import org.signal.imageeditor.core.model.EditorModel
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.conversation.MessageSendType
import org.thoughtcrime.securesms.conversation.NativeLocationSource
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
import org.thoughtcrime.securesms.mediasend.ProofConstants
import org.thoughtcrime.securesms.mediasend.ProofConstants.IS_PROOF_ENABLED
import org.thoughtcrime.securesms.mediasend.ProofConstants.PROOF_OBJECT
import org.thoughtcrime.securesms.mediasend.SentMediaQualityTransform
import org.thoughtcrime.securesms.mediasend.VideoEditorFragment
import org.thoughtcrime.securesms.mediasend.VideoTrimTransform
import org.thoughtcrime.securesms.mediasend.proofmode.MobileCoinNotaryUtil
import org.thoughtcrime.securesms.mediasend.proofmode.MobileCoinObject
import org.thoughtcrime.securesms.mediasend.proofmode.MobileCoinProofUtil
import org.thoughtcrime.securesms.mediasend.proofmode.ProofModeUtil
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage
import org.thoughtcrime.securesms.mms.OutgoingSecureMediaMessage
import org.thoughtcrime.securesms.mms.SentMediaQuality
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.payments.MobileCoinConfig
import org.thoughtcrime.securesms.proofFromJson
import org.thoughtcrime.securesms.providers.BlobProvider
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.scribbles.ImageEditorFragment
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.sms.MessageSender.PreUploadResult
import org.thoughtcrime.securesms.stories.Stories
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.MessageUtil
import java.util.Collections
import java.util.Locale
import java.util.Optional
import java.util.concurrent.TimeUnit


private val TAG = Log.tag(MediaSelectionRepository::class.java)

@OptIn(ExperimentalCoroutinesApi::class)
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


      var recipient: RecipientId? = singleContact?.let { it.recipientId }

      if (contacts.isNotEmpty()) {
        recipient = contacts[0].recipientId
      }

      var proofHash = selectedMedia.first().proofHash

      var notarize = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(ProofConstants.IS_PROOF_NOTARY_ENABLED_GLOBAL, true);

      if (notarize) {
        recipient?.let {
          MobileCoinNotaryUtil().notarize(recipient, MobileCoinNotaryUtil.DEFAULT_NOTARIZATION_AMOUNT, selectedMedia[0].proofHash.substring(0, 16))

          //in the future, this is where we will add the notarization details into the zip proof directory


        }
      }

      val file = ProofModeUtil.createZipProof(proofHash, context)
      newMediaList.add(
        Media(
          requireNotNull(Uri.fromFile(file)),
          MediaUtil.OCTET,
          proofHash,
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

  lateinit var mobileCoinConfig: MobileCoinConfig

  private fun mobileCoinSetup(): MobileCoinObject {
//    val paymentsEntropy = SignalStore.paymentsValues().paymentsEntropy!!
    val paymentsEntropy = DefaultRng.createInstance().nextBytes(32)

    // Create account key or access to the same one (need implementation)
    val key = AccountKey.fromBip39Entropy(
      paymentsEntropy,
      0,
      MobileCoinProofUtil.getFogReportUri(),
      "",
      ByteArray(0)
    )

    // Should save phrase
    val myRecoveryPhrase = Mnemonics.bip39EntropyToMnemonic(paymentsEntropy) // Save this securely! It is required to access the account

    // Create client from new user to prepare transaction
    val client = MobileCoinClient(
      key,
      MobileCoinProofUtil.getFogUri(),
      MobileCoinProofUtil.getConsensusUri(),
      TransportProtocol.forGRPC()
    )


    // Later, to access the same account, I recommend the following
/*    val restoredKey = AccountKey.fromMnemonicPhrase(
      myRecoveryPhrase,
      0,
      MobileCoinProofUtil.getFogReportUri(),
      "", ByteArray(0))
    val existingClient = MobileCoinClient(
      key,
      MobileCoinProofUtil.getFogUri(),
      MobileCoinProofUtil.getConsensusUri(),
      TransportProtocol.forGRPC()
    )*/


    val publicAddress = key.publicAddress
    val wrapper: PrintableWrapper = PrintableWrapper.fromPublicAddress(publicAddress)
    val printablePubAddress: String = wrapper.toB58String()

    val accountSnapshot: AccountSnapshot = client.accountSnapshot

    /**
     * UPDATES TOMORROW 12.01.23
     */
/*    val pendingTransaction = if (accountSnapshot != null) {
      accountSnapshot.prepareTransaction(
        publicAddress,
        Amount.ofMOB(0),
        Amount.ofMOB(0),
        TxOutMemoBuilder.createSenderPaymentIntentAndDestinationRTHMemoBuilder(key, ""))
    } else {
      client.prepareTransaction(publicAddress,
        Amount.ofMOB(0),
        Amount.ofMOB(0),
        TxOutMemoBuilder.createSenderPaymentIntentAndDestinationRTHMemoBuilder(key, ""))
    }
    client.submitTransaction(pendingTransaction.transaction)

    client.getTransactionStatus(pendingTransaction.transaction)

    Transaction.Status*/


    // Set address to object
    return MobileCoinObject(
      publicAddress = printablePubAddress
    )
  }

  private fun getCityName(lat: Double, long: Double): String {
    var addressResult = ""
    Geocoder(context, Locale.getDefault())
      .getAddress(lat, long) { address: android.location.Address? ->
        if (address != null) {
          addressResult = address.adminArea + ", " + address.countryCode
        }
      }
    return addressResult
  }

  @Suppress("DEPRECATION")
  fun Geocoder.getAddress(
    latitude: Double,
    longitude: Double,
    address: (android.location.Address?) -> Unit
  ) {

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      getFromLocation(latitude, longitude, 1) { address(it.firstOrNull()) }
      return
    }

    try {
      address(getFromLocation(latitude, longitude, 1)?.firstOrNull())
    } catch (e: Exception) {
      //will catch if there is an internet problem
      address(null)
    }
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
      val newBody = if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(IS_PROOF_ENABLED, true)) {
        var location: Location? = null
        runBlocking {
          val locationSource = NativeLocationSource(context, LocationServices.getFusedLocationProviderClient(context))
          location = locationSource.getLocationUpdates().first()
        }
        val city = getCityName(location?.latitude ?: 0.0, location?.longitude ?: 0.0)
        val proofJson = PreferenceManager.getDefaultSharedPreferences(context).getString(PROOF_OBJECT, "").orEmpty()
        if (proofJson.isNotEmpty()) {
          val proofObject = JSONObject(proofJson).proofFromJson()

          val proofListString = if (proofObject.proofsList.isNotEmpty()) {
            proofObject.proofsList.map { it.title }.toString().dropLast(1).drop(1)
          } else {
            "None"
          }
          val latitude = try {
            proofObject.latitude.toDouble()
          } catch (ex: Exception) {
            0.0
          }
          val longitude = try {
            proofObject.longitude.toDouble()
          } catch (ex: Exception) {
            0.0
          }
          Log.e("OBJECT:", "$proofObject")
          val headerString = "ProofMode info: \n" +
            "Taken: ${ProofModeUtil.formatProofTimeString(proofObject.time)} UTC" +
            "\nNear: $city, ${ProofModeUtil.convert(latitude, longitude)}" +
            "\nProofs: $proofListString" +
            "\nNetwork Type: ${proofObject.networkType}" +
            "\nDevice Name: ${proofObject.deviceName} " + "Android v.${Build.VERSION.RELEASE}" +
            "\nMOB TX: ${proofObject.notaryTx}" +
            "\nProofs were checked and verified"
          headerString + "\n" + body
        } else {
          "Taken: ${ProofModeUtil.convertLongToTime(System.currentTimeMillis())} UTC" +
            "\nProofs were checked and verified"
        }
      } else {
        body
      }
      val message = OutgoingMediaMessage(
        recipient,
        newBody,
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
              OutgoingSecureMediaMessage(if (isChanged) emptyTextMessage else message).withSentTimestamp(
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
