package org.thoughtcrime.securesms.mediasend.proofmode;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;

import org.signal.core.util.concurrent.SignalExecutors;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.payments.Payee;
import org.thoughtcrime.securesms.payments.PaymentsAddressException;
import org.thoughtcrime.securesms.payments.Wallet;
import org.thoughtcrime.securesms.payments.confirm.ConfirmPaymentRepository;
import org.thoughtcrime.securesms.payments.confirm.ConfirmPaymentState;
import org.thoughtcrime.securesms.payments.confirm.ConfirmPaymentViewModel;
import org.thoughtcrime.securesms.payments.create.CreatePaymentViewModel;
import org.thoughtcrime.securesms.payments.preferences.model.PayeeParcelable;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.livedata.LiveDataUtil;
import org.whispersystems.signalservice.api.payments.Money;

import java.math.BigDecimal;

public class MobileCoinNotaryUtil {

  public final static String DEFAULT_NOTARIZATION_AMOUNT = "0.01";

  public void notarize (RecipientId recipient, String mcAmount, String note) {

    Payee payee  = new Payee(recipient);
    Money amount = Money.mobileCoin(new BigDecimal(mcAmount));

    ConfirmPaymentState      state = new ConfirmPaymentState(payee, amount, note);

    SignalExecutors.BOUNDED.execute(() -> {
      Wallet wallet = ApplicationDependencies.getPayments().getWallet();
      wallet.getFullLedger();
      wallet.refresh();

      ConfirmPaymentRepository repo  = new ConfirmPaymentRepository(wallet);
      ConfirmPaymentViewModel model = new ConfirmPaymentViewModel(state, repo);

      while (state.getFeeStatus() != ConfirmPaymentState.FeeStatus.SET)
      {
        ConfirmPaymentRepository.GetFeeResult result = model.getFee(amount);
        if (result instanceof ConfirmPaymentRepository.GetFeeResult.Success) {
          Money fee = ((ConfirmPaymentRepository.GetFeeResult.Success) result).getFee();
          //if we have the fee, we can now confirm payment, so break this loop
          break;
        }

        //wait one second to get updated status fee
        try { Thread.sleep(1000);}catch (Exception e){}
      }

      //then wait 3 more seconds... why? I don't because it works!
      try { Thread.sleep(3000);}catch (Exception e){}

      //now confirm payment
      model.confirmPayment();

    });
  }

}
