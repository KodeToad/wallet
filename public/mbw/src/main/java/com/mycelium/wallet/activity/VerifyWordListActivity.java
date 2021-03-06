/*
 * Copyright 2013, 2014 Megion Research and Development GmbH
 *
 * Licensed under the Microsoft Reference Source License (MS-RSL)
 *
 * This license governs use of the accompanying software. If you use the software, you accept this license.
 * If you do not accept the license, do not use the software.
 *
 * 1. Definitions
 * The terms "reproduce," "reproduction," and "distribution" have the same meaning here as under U.S. copyright law.
 * "You" means the licensee of the software.
 * "Your company" means the company you worked for when you downloaded the software.
 * "Reference use" means use of the software within your company as a reference, in read only form, for the sole purposes
 * of debugging your products, maintaining your products, or enhancing the interoperability of your products with the
 * software, and specifically excludes the right to distribute the software outside of your company.
 * "Licensed patents" means any Licensor patent claims which read directly on the software as distributed by the Licensor
 * under this license.
 *
 * 2. Grant of Rights
 * (A) Copyright Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free copyright license to reproduce the software for reference use.
 * (B) Patent Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free patent license under licensed patents for reference use.
 *
 * 3. Limitations
 * (A) No Trademark License- This license does not grant you any rights to use the Licensor’s name, logo, or trademarks.
 * (B) If you begin patent litigation against the Licensor over patents that you think may apply to the software
 * (including a cross-claim or counterclaim in a lawsuit), your license to the software ends automatically.
 * (C) The software is licensed "as-is." You bear the risk of using it. The Licensor gives no express warranties,
 * guarantees or conditions. You may have additional consumer rights under your local laws which this license cannot
 * change. To the extent permitted under your local laws, the Licensor excludes the implied warranties of merchantability,
 * fitness for a particular purpose and non-infringement.
 */

package com.mycelium.wallet.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.google.common.collect.Lists;
import com.mrd.bitlib.crypto.Bip39;
import com.mycelium.wallet.MbwManager;
import com.mycelium.wallet.R;
import com.mycelium.wallet.Utils;
import com.mycelium.wallet.persistence.MetadataStorage;
import com.mycelium.wapi.wallet.AesKeyCipher;
import com.mycelium.wapi.wallet.KeyCipher;

import java.util.List;

public class VerifyWordListActivity extends ActionBarActivity implements WordAutoCompleterFragment.WordAutoCompleterListener {
   private MbwManager _mbwManager;
   private List<String> wordlist;
   private String password;
   private int currentWordIndex;
   private WordAutoCompleterFragment _wordAutoCompleter;

   public static void callMe(Activity activity) {
      Intent intent = new Intent(activity, VerifyWordListActivity.class);
      activity.startActivity(intent);
   }


   @Override
   public void onCreate(Bundle savedInstanceState) {
      this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
      this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
      super.onCreate(savedInstanceState);
      setContentView(R.layout.activity_verify_words);
      Utils.preventScreenshots(this);
      _mbwManager = MbwManager.getInstance(this);
      Bip39.MasterSeed masterSeed;
      try {
         masterSeed = _mbwManager.getWalletManager(false).getMasterSeed(AesKeyCipher.defaultKeyCipher());
      } catch (KeyCipher.InvalidKeyCipher invalidKeyCipher) {
         throw new RuntimeException(invalidKeyCipher);
      }

      wordlist = masterSeed.getBip39WordList();
      password = masterSeed.getBip39Password();
      currentWordIndex = 0;

      _wordAutoCompleter = (WordAutoCompleterFragment) getSupportFragmentManager().findFragmentById(R.id.wordAutoCompleter);
      _wordAutoCompleter.setListener(this);
      _wordAutoCompleter.setMinimumCompletionCharacters(2);
      _wordAutoCompleter.setCompletions(Bip39.ENGLISH_WORD_LIST);
      UsKeyboardFragment keyboard = (UsKeyboardFragment) getSupportFragmentManager().findFragmentById(R.id.usKeyboard);
      keyboard.setListener(_wordAutoCompleter);
   }

   private void askForNextWord() {
      if (currentWordIndex == wordlist.size() - 1) {
         //we are done, final steps
         askForPassword(false);
      } else {
         //ask for next word
         currentWordIndex++;
         _wordAutoCompleter.setHintText(getString(R.string.importing_wordlist_enter_next_word, currentWordIndex + 1, wordlist.size()));
      }
   }

   private void askForPassword(boolean wasWrong) {
      if (password.length() > 0) {
         final EditText pass = new EditText(this);

         final AlertDialog.Builder builder = new AlertDialog.Builder(this);
         if (wasWrong) {
            builder.setTitle(R.string.title_wrong_password);
         } else {
            builder.setTitle(R.string.type_password_title);
         }
         builder.setView(pass)
               .setCancelable(false)
               .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                  public void onClick(DialogInterface dialog, int id) {
                     if (pass.getText().toString().equals(password)) {
                        setVerified();
                     } else {
                        askForPassword(true);
                     }
                  }
               })
               .show();
      } else {
         setVerified();
      }
   }

   private void setVerified() {
      _mbwManager.getMetadataStorage().setMasterKeyBackupState(MetadataStorage.BackupState.VERIFIED);
      Utils.showSimpleMessageDialog(this, R.string.verify_wordlist_success, new Runnable() {
         @Override
         public void run() {
            VerifyWordListActivity.this.finish();
         }
      });
   }

   @Override
   public void onSaveInstanceState(Bundle savedInstanceState)
   {
      super.onSaveInstanceState(savedInstanceState);
      savedInstanceState.putInt("index", currentWordIndex);
   }

   @Override
   public void onRestoreInstanceState(Bundle savedInstanceState)
   {
      super.onRestoreInstanceState(savedInstanceState);
      currentWordIndex = savedInstanceState.getInt("index");
      _wordAutoCompleter.setHintText(getString(R.string.importing_wordlist_enter_next_word, currentWordIndex + 1, wordlist.size()));
   }

   @Override
   public void onWordSelected(String word) {
      if (word.equals(wordlist.get(currentWordIndex))) {
         askForNextWord();
      } else {
         Toast.makeText(VerifyWordListActivity.this, R.string.verify_word_wrong, Toast.LENGTH_LONG).show();
      }
   }
}
