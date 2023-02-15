package org.thoughtcrime.securesms.mediasend.proofmode;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;

import org.signal.core.util.concurrent.SignalExecutors;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.payments.Payee;
import org.thoughtcrime.securesms.payments.PaymentsAddressException;
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

  public final static String DEFAULT_NOTARIZATION_AMOUNT = "0.1";

  public CreatePaymentViewModel createPaymentViewModel (String recipientId, String note) {
    PayeeParcelable payee = new PayeeParcelable(new Payee(RecipientId.fromE164(recipientId)));
    CreatePaymentViewModel model = new CreatePaymentViewModel.Factory(payee, note).create(CreatePaymentViewModel.class);

    return model;
  }

  public void notarize (RecipientId recipient, String mcAmount, String note) {

    Payee payee  = new Payee(recipient);
    Money amount = Money.mobileCoin(new BigDecimal(mcAmount));

    ConfirmPaymentState      state = new ConfirmPaymentState(payee, amount, note);

    SignalExecutors.BOUNDED.execute(() -> {
      ConfirmPaymentRepository repo  = new ConfirmPaymentRepository(ApplicationDependencies.getPayments().getWallet());
      ConfirmPaymentViewModel model = new ConfirmPaymentViewModel(state, repo);

      while (state.getFeeStatus() != ConfirmPaymentState.FeeStatus.SET)
      {
        ConfirmPaymentRepository.GetFeeResult result = model.getFee(amount);
        if (result instanceof ConfirmPaymentRepository.GetFeeResult.Success) {
          Money fee = ((ConfirmPaymentRepository.GetFeeResult.Success) result).getFee();
          ConfirmPaymentState newState = state.updateFee(fee);
          break;
        }

        try { Thread.sleep(1000);}catch (Exception e){}
      }
      //model.getFee(amount);
      model.confirmPayment();

    });
  }

}
