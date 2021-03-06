/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.textsecure.api.messages;

import android.util.Log;

import com.google.protobuf.ByteString;

import org.whispersystems.libaxolotl.InvalidVersionException;
import org.whispersystems.textsecure.internal.push.PushMessageProtos.IncomingPushMessageSignal;
import org.whispersystems.textsecure.internal.util.Base64;
import org.whispersystems.textsecure.internal.util.Hex;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class TextSecureEnvelope {

  private static final String TAG = TextSecureEnvelope.class.getSimpleName();

  private static final int SUPPORTED_VERSION =  1;
  private static final int CIPHER_KEY_SIZE   = 32;
  private static final int MAC_KEY_SIZE      = 20;
  private static final int MAC_SIZE          = 10;

  private static final int VERSION_OFFSET    =  0;
  private static final int VERSION_LENGTH    =  1;
  private static final int IV_OFFSET         = VERSION_OFFSET + VERSION_LENGTH;
  private static final int IV_LENGTH         = 16;
  private static final int CIPHERTEXT_OFFSET = IV_OFFSET + IV_LENGTH;

  private final IncomingPushMessageSignal signal;

  public TextSecureEnvelope(String message, String signalingKey)
      throws IOException, InvalidVersionException
  {
    this(Base64.decode(message), signalingKey);
  }

  public TextSecureEnvelope(byte[] ciphertext, String signalingKey)
      throws InvalidVersionException, IOException
  {
    if (ciphertext.length < VERSION_LENGTH || ciphertext[VERSION_OFFSET] != SUPPORTED_VERSION)
      throw new InvalidVersionException("Unsupported version!");

    SecretKeySpec cipherKey  = getCipherKey(signalingKey);
    SecretKeySpec macKey     = getMacKey(signalingKey);

    verifyMac(ciphertext, macKey);

    this.signal = IncomingPushMessageSignal.parseFrom(getPlaintext(ciphertext, cipherKey));
  }

  public TextSecureEnvelope(int type, String source, int sourceDevice,
                            String relay, long timestamp, byte[] message)
  {
    this.signal = IncomingPushMessageSignal.newBuilder()
                                           .setType(IncomingPushMessageSignal.Type.valueOf(type))
                                           .setSource(source)
                                           .setSourceDevice(sourceDevice)
                                           .setRelay(relay)
                                           .setTimestamp(timestamp)
                                           .setMessage(ByteString.copyFrom(message))
                                           .build();
  }

  public String getSource() {
    return signal.getSource();
  }

  public int getSourceDevice() {
    return signal.getSourceDevice();
  }

  public int getType() {
    return signal.getType().getNumber();
  }

  public String getRelay() {
    return signal.getRelay();
  }

  public long getTimestamp() {
    return signal.getTimestamp();
  }

  public byte[] getMessage() {
    return signal.getMessage().toByteArray();
  }

  public boolean isWhisperMessage() {
    return signal.getType().getNumber() == IncomingPushMessageSignal.Type.CIPHERTEXT_VALUE;
  }

  public boolean isPreKeyWhisperMessage() {
    return signal.getType().getNumber() == IncomingPushMessageSignal.Type.PREKEY_BUNDLE_VALUE;
  }

  public boolean isPlaintext() {
    return signal.getType().getNumber() == IncomingPushMessageSignal.Type.PLAINTEXT_VALUE;
  }

  public boolean isReceipt() {
    return signal.getType().getNumber() == IncomingPushMessageSignal.Type.RECEIPT_VALUE;
  }

  private byte[] getPlaintext(byte[] ciphertext, SecretKeySpec cipherKey) throws IOException {
    try {
      byte[] ivBytes = new byte[IV_LENGTH];
      System.arraycopy(ciphertext, IV_OFFSET, ivBytes, 0, ivBytes.length);
      IvParameterSpec iv = new IvParameterSpec(ivBytes);

      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(Cipher.DECRYPT_MODE, cipherKey, iv);

      return cipher.doFinal(ciphertext, CIPHERTEXT_OFFSET,
                            ciphertext.length - VERSION_LENGTH - IV_LENGTH - MAC_SIZE);
    } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException e) {
      throw new AssertionError(e);
    } catch (BadPaddingException e) {
      Log.w(TAG, e);
      throw new IOException("Bad padding?");
    }
  }

  private void verifyMac(byte[] ciphertext, SecretKeySpec macKey) throws IOException {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(macKey);

      if (ciphertext.length < MAC_SIZE + 1)
        throw new IOException("Invalid MAC!");

      mac.update(ciphertext, 0, ciphertext.length - MAC_SIZE);

      byte[] ourMacFull  = mac.doFinal();
      byte[] ourMacBytes = new byte[MAC_SIZE];
      System.arraycopy(ourMacFull, 0, ourMacBytes, 0, ourMacBytes.length);

      byte[] theirMacBytes = new byte[MAC_SIZE];
      System.arraycopy(ciphertext, ciphertext.length-MAC_SIZE, theirMacBytes, 0, theirMacBytes.length);

      Log.w(TAG, "Our MAC: " + Hex.toString(ourMacBytes));
      Log.w(TAG, "Thr MAC: " + Hex.toString(theirMacBytes));

      if (!Arrays.equals(ourMacBytes, theirMacBytes)) {
        throw new IOException("Invalid MAC compare!");
      }
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new AssertionError(e);
    }
  }


  private SecretKeySpec getCipherKey(String signalingKey) throws IOException {
    byte[] signalingKeyBytes = Base64.decode(signalingKey);
    byte[] cipherKey         = new byte[CIPHER_KEY_SIZE];
    System.arraycopy(signalingKeyBytes, 0, cipherKey, 0, cipherKey.length);

    return new SecretKeySpec(cipherKey, "AES");
  }


  private SecretKeySpec getMacKey(String signalingKey) throws IOException {
    byte[] signalingKeyBytes = Base64.decode(signalingKey);
    byte[] macKey            = new byte[MAC_KEY_SIZE];
    System.arraycopy(signalingKeyBytes, CIPHER_KEY_SIZE, macKey, 0, macKey.length);

    return new SecretKeySpec(macKey, "HmacSHA256");
  }

}
