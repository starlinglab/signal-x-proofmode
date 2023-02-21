package org.thoughtcrime.securesms.payments.confirm;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.google.protobuf.ByteString;

import org.signal.core.util.logging.Log;
import org.signal.core.util.money.FiatMoney;
import org.thoughtcrime.securesms.database.PaymentTable.PaymentTransaction;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.payments.CreatePaymentDetails;
import org.thoughtcrime.securesms.payments.FiatMoneyUtil;
import org.thoughtcrime.securesms.payments.MobileCoinLedgerWrapper;
import org.thoughtcrime.securesms.payments.Payee;
import org.thoughtcrime.securesms.payments.Payment;
import org.thoughtcrime.securesms.payments.PaymentTransactionLiveData;
import org.thoughtcrime.securesms.payments.Payments;
import org.thoughtcrime.securesms.payments.PaymentsAddressException;
import org.thoughtcrime.securesms.payments.Wallet;
import org.thoughtcrime.securesms.payments.confirm.ConfirmPaymentRepository.ConfirmPaymentResult;
import org.thoughtcrime.securesms.payments.proto.PaymentMetaData;
import org.thoughtcrime.securesms.util.DefaultValueLiveData;
import org.thoughtcrime.securesms.util.SingleLiveEvent;
import org.thoughtcrime.securesms.util.livedata.LiveDataUtil;
import org.thoughtcrime.securesms.util.livedata.Store;
import org.whispersystems.signalservice.api.payments.Money;
import org.whispersystems.util.Base64;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final public class ConfirmPaymentViewModel extends ViewModel {

  private static final String TAG = Log.tag(ConfirmPaymentViewModel.class);

  private final Store<ConfirmPaymentState> store;
  private final ConfirmPaymentRepository   confirmPaymentRepository;
  private final LiveData<Boolean>          paymentDone;
  private final SingleLiveEvent<ErrorType> errorEvents;
  private final MutableLiveData<Boolean>   feeRetry;

  public ConfirmPaymentViewModel(@NonNull ConfirmPaymentState confirmPaymentState,
                          @NonNull ConfirmPaymentRepository confirmPaymentRepository)
  {
    this.store                    = new Store<>(confirmPaymentState);
    this.confirmPaymentRepository = confirmPaymentRepository;
    this.errorEvents              = new SingleLiveEvent<>();
    this.feeRetry                 = new DefaultValueLiveData<>(true);

    this.store.update(SignalStore.paymentsValues().liveMobileCoinBalance(), (balance, state) -> state.updateBalance(balance.getFullAmount()));

    LiveData<Boolean> longLoadTime = LiveDataUtil.delay(1000, true);
    this.store.update(longLoadTime, (l, s) -> {
      if (s.getFeeStatus() == ConfirmPaymentState.FeeStatus.NOT_SET) return s.updateFeeStillLoading();
      else {
          return s;

      }
    });

    LiveData<Money> amount = Transformations.distinctUntilChanged(Transformations.map(store.getStateLiveData(), ConfirmPaymentState::getAmount));
    this.store.update(LiveDataUtil.mapAsync(LiveDataUtil.combineLatest(amount, feeRetry, (a, f) -> a), this::getFee), (feeResult, state) -> {
      if      (feeResult instanceof ConfirmPaymentRepository.GetFeeResult.Success) return state.updateFee(((ConfirmPaymentRepository.GetFeeResult.Success) feeResult).getFee());
      else if (feeResult instanceof ConfirmPaymentRepository.GetFeeResult.Error)   return state.updateFeeError();
      else throw new AssertionError();
    });

    LiveData<UUID>               paymentId           = Transformations.distinctUntilChanged(Transformations.map(store.getStateLiveData(), ConfirmPaymentState::getPaymentId));
    LiveData<PaymentTransaction> transactionLiveData = Transformations.switchMap(paymentId, id -> (id != null) ? new PaymentTransactionLiveData(id) : new MutableLiveData<>());
    this.store.update(transactionLiveData, this::handlePaymentTransactionChanged);

    this.paymentDone = Transformations.distinctUntilChanged(Transformations.map(store.getStateLiveData(), state -> state.getStatus().isTerminalStatus()));

    LiveData<Optional<FiatMoney>> exchange = FiatMoneyUtil.getExchange(amount);
    this.store.update(exchange, (exchange1, confirmPaymentState1) -> confirmPaymentState1.updateExchange(exchange1.orElse(null)));

    LiveData<ConfirmPaymentState.Status> statusLiveData = Transformations.map(store.getStateLiveData(), ConfirmPaymentState::getStatus);
    LiveData<ConfirmPaymentState.Status> timeoutSignal  = Transformations.switchMap(statusLiveData,
                                                                                    s -> {
                                                                                      if (s == ConfirmPaymentState.Status.PROCESSING) {
                                                                                        Log.i(TAG, "Beginning timeout timer");
                                                                                        return LiveDataUtil.delay(TimeUnit.SECONDS.toMillis(20), s);
                                                                                      } else {
                                                                                        return LiveDataUtil.never();
                                                                                      }
                                                                                    });

    this.store.update(timeoutSignal, this::handleTimeout);
  }

  @NonNull public LiveData<ConfirmPaymentState> getState() {
    return store.getStateLiveData();
  }

  @NonNull LiveData<Boolean> isPaymentDone() {
    return paymentDone;
  }

  @NonNull LiveData<ErrorType> getErrorTypeEvents() {
    return errorEvents;
  }

  public void confirmPayment() {
    store.update(state -> state.updateStatus(ConfirmPaymentState.Status.SUBMITTING));
    confirmPaymentRepository.confirmPayment(store.getState(), this::handleConfirmPaymentResult);
  }

  @Override
  protected void onCleared() {
    super.onCleared();
    store.clear();
  }

  public void refreshFee() {
    feeRetry.setValue(true);
  }

  public @NonNull ConfirmPaymentRepository.GetFeeResult getFee(@NonNull Money amount) {
    ConfirmPaymentRepository.GetFeeResult result = confirmPaymentRepository.getFee(amount);

    if (result instanceof ConfirmPaymentRepository.GetFeeResult.Error) {
      errorEvents.postValue(ErrorType.CAN_NOT_GET_FEE);
    }
    else if (result instanceof ConfirmPaymentRepository.GetFeeResult.Success) {
      Money fee = ((ConfirmPaymentRepository.GetFeeResult.Success)result).getFee();
      store.update(state -> this.store.getState().updateFee(fee));
    }


    return result;
  }

  private void handleConfirmPaymentResult(@NonNull ConfirmPaymentResult result) {
    if (result instanceof ConfirmPaymentResult.Success) {
      ConfirmPaymentResult.Success success = (ConfirmPaymentResult.Success) result;

      UUID   paymentId = ((ConfirmPaymentResult.Success) result).getPaymentId();
      Wallet wallet    = ApplicationDependencies.getPayments().getWallet();
      List<MobileCoinLedgerWrapper.OwnedTxo> txs = wallet.getFullLedger().getAllTxos();

      boolean txNotFound = true;

      while (txNotFound)
      {
        PaymentTransaction pt = SignalDatabase.payments()
                      .getPayment(paymentId);
        PaymentMetaData mdata  = pt.getPaymentMetaData();

        int pubKeyCount = mdata.getMobileCoinTxoIdentification().getPublicKeyCount();
        if (pubKeyCount == 0)
          continue;

        ByteString      pubKey = mdata.getMobileCoinTxoIdentification().getPublicKey(0);
        Payee           payee = pt.getPayee();

        Log.w(TAG, "Confirming payment id: " + paymentId.toString());

        if (pubKey != null && pubKey.toByteArray() != null) {

          txs = wallet.getFullLedger().getAllTxos();

          String           txPubKey      = bytesToHex(pubKey.toByteArray());
          long             blockTs       = pt.getBlockTimestamp();
          long             ts            = pt.getTimestamp();
          Money.MobileCoin money         = (Money.MobileCoin) pt.getAmount();
          String           amountDecimal = money.getAmountDecimalString();

          Log.w(TAG, "txPubKey:" + txPubKey + " ts:" + new Date(ts) + " amount:" + amountDecimal);
          txNotFound = false;
          break;
        }

        Log.w(TAG,"waiting for transaction...");
        //wait one second to get updated status fee
        try { Thread.sleep(1000);}catch (Exception e){}

      }


      store.update(state -> state.updatePaymentId(success.getPaymentId()));
    } else if (result instanceof ConfirmPaymentResult.Error) {
      ConfirmPaymentResult.Error    error = (ConfirmPaymentResult.Error) result;
      PaymentsAddressException.Code code  = error.getCode();

      store.update(state -> state.updateStatus(ConfirmPaymentState.Status.ERROR));
      if (code != null) {
        errorEvents.postValue(getErrorType(code));
      }
    } else {
      throw new AssertionError();
    }
  }

  private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
  public static String bytesToHex(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for (int j = 0; j < bytes.length; j++) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 2] = HEX_ARRAY[v >>> 4];
      hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
    }
    return new String(hexChars);
  }

  private @NonNull ErrorType getErrorType(@NonNull PaymentsAddressException.Code code) {
    switch (code) {
      case NO_PROFILE_KEY:
        return ErrorType.NO_PROFILE_KEY;
      case COULD_NOT_DECRYPT:
      case NOT_ENABLED:
      case INVALID_ADDRESS:
      case INVALID_ADDRESS_SIGNATURE:
      case NO_ADDRESS:
        return ErrorType.NO_ADDRESS;
    }

    throw new AssertionError();
  }

  private @NonNull ConfirmPaymentState handlePaymentTransactionChanged(@Nullable PaymentTransaction paymentTransaction, @NonNull ConfirmPaymentState state) {
    if (paymentTransaction == null) {
      return state;
    }

    if (state.getStatus().isTerminalStatus()) {
      Log.w(TAG, "Payment already in a final state on transaction change");
      return state;
    }

    switch (paymentTransaction.getState()) {
      case INITIAL:    return state.updateStatus(ConfirmPaymentState.Status.SUBMITTING);
      case SUBMITTED:  return state.updateStatus(ConfirmPaymentState.Status.PROCESSING);
      case SUCCESSFUL: return state.updateStatus(ConfirmPaymentState.Status.DONE);
      case FAILED:     return state.updateStatus(ConfirmPaymentState.Status.ERROR);
      default:         throw new AssertionError();
    }
  }

  private @NonNull ConfirmPaymentState handleTimeout(@NonNull ConfirmPaymentState.Status status, @NonNull ConfirmPaymentState state) {
    if (state.getStatus().isTerminalStatus()) {
      Log.w(TAG, "Payment already in a final state on timeout");
      return state;
    }

    Log.w(TAG, "Timed out while in " + status);
    return state.timeout();
  }

  enum ErrorType {
    NO_PROFILE_KEY,
    NO_ADDRESS,
    CAN_NOT_GET_FEE
  }

  static final class Factory implements ViewModelProvider.Factory {
    private final CreatePaymentDetails createPaymentDetails;

    public Factory(@NonNull CreatePaymentDetails createPaymentDetails) {
      this.createPaymentDetails = createPaymentDetails;
    }

    @Override
    public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      //noinspection ConstantConditions
      return modelClass.cast(new ConfirmPaymentViewModel(new ConfirmPaymentState(createPaymentDetails.getPayee(),
                                                                                 createPaymentDetails.getAmount(),
                                                                                 createPaymentDetails.getNote()),
                                                         new ConfirmPaymentRepository(ApplicationDependencies.getPayments().getWallet())));
    }
  }
}
