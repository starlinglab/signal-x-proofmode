package org.thoughtcrime.securesms.mediasend.proofmode

import android.net.Uri
import com.google.protobuf.ByteString
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.payments.Payee
import org.thoughtcrime.securesms.payments.confirm.ConfirmPaymentViewModel
import org.thoughtcrime.securesms.payments.create.CreatePaymentViewModel
import org.thoughtcrime.securesms.payments.preferences.model.PayeeParcelable
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.Base64

public object MobileCoinProofUtil {

  // copy from MobileCoinMainNetConfig
  fun getFogAuthoritySpki(): ByteArray {
    return Base64.decodeOrThrow("""
  MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAxaNIOgcoQtq0S64dFVha
  6rn0hDv/ec+W0cKRdFKygiyp5xuWdW3YKVAkK1PPgSDD2dwmMN/1xcGWrPMqezx1
  h1xCzbr7HL7XvLyFyoiMB2JYd7aoIuGIbHpCOlpm8ulVnkOX7BNuo0Hi2F0AAHyT
  PwmtVMt6RZmae1Z/Pl2I06+GgWN6vufV7jcjiLT3yQPsn1kVSj+DYCf3zq+1sCkn
  KIvoRPMdQh9Vi3I/fqNXz00DSB7lt3v5/FQ6sPbjljqdGD/qUl4xKRW+EoDLlAUf
  zahomQOLXVAlxcws3Ua5cZUhaJi6U5jVfw5Ng2N7FwX/D5oX82r9o3xcFqhWpGnf
  SxSrAudv1X7WskXomKhUzMl/0exWpcJbdrQWB/qshzi9Et7HEDNY+xEDiwGiikj5
  f0Lb+QA4mBMlAhY/cmWec8NKi1gf3Dmubh6c3sNteb9OpZ/irA3AfE8jI37K1rve
  zDI8kbNtmYgvyhfz0lZzRT2WAfffiTe565rJglvKa8rh8eszKk2HC9DyxUb/TcyL
  /OjGhe2fDYO2t6brAXCqjPZAEkVJq3I30NmnPdE19SQeP7wuaUIb3U7MGxoZC/Nu
  JoxZh8svvZ8cyqVjG+dOQ6/UfrFY0jiswT8AsrfqBis/ZV5EFukZr+zbPtg2MH0H
  3tSJ14BCLduvc7FY6lAZmOcCAwEAAQ==
  """.trimIndent())
  }

  // copy from MobileCoinMainNetConfig
  fun getFogReportUri(): Uri {
    return Uri.parse("fog://fog-rpt-prd.namda.net")
  }

  fun getConsensusUri(): Uri {
    return Uri.parse("mc://node1.prod.mobilecoinww.com")
  }

  fun getFogUri(): Uri {
    return Uri.parse("fog://service.fog.mob.production.namda.net")
  }

  fun getTxFromHash(proofHash : String): String {

    val proofHashShort = proofHash.substring(0,16)

    val payments = SignalDatabase.payments.all

    for (payment in payments) {
      if (payment.note == proofHashShort) {

        val pubKeyCount: Int? = payment.paymentMetaData?.mobileCoinTxoIdentification?.getPublicKeyCount()
        if (pubKeyCount == 0) break

        val pubKey: ByteString? = payment.paymentMetaData?.mobileCoinTxoIdentification?.getPublicKey(0)

        pubKey?.let {
          var txId = ConfirmPaymentViewModel.bytesToHex(pubKey.toByteArray())
          return txId
        }

        break;
      }
    }

    return ""
  }

}

data class MobileCoinObject(
  val publicAddress: String? = null,
  val memoLink: String? = null
)