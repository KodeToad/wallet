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

package com.mycelium.wallet.wapi;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;
import com.google.common.base.Preconditions;
import com.mrd.bitlib.model.Address;
import com.mrd.bitlib.model.OutPoint;
import com.mrd.bitlib.util.HexUtils;
import com.mrd.bitlib.util.Sha256Hash;
import com.mycelium.wallet.persistence.SQLiteQueryWithBlobs;
import com.mycelium.wapi.model.TransactionEx;
import com.mycelium.wapi.model.TransactionOutputEx;
import com.mycelium.wapi.wallet.Bip44AccountBacking;
import com.mycelium.wapi.wallet.SingleAddressAccountBacking;
import com.mycelium.wapi.wallet.WalletManagerBacking;
import com.mycelium.wapi.wallet.bip44.Bip44AccountContext;
import com.mycelium.wapi.wallet.single.SingleAddressAccountContext;

import java.util.*;

public class SqliteWalletManagerBacking implements WalletManagerBacking {

   private static final String LOG_TAG = "SqliteAccountBacking";
   private static final String TABLE_KV = "kv";

   private class OpenHelper extends SQLiteOpenHelper {

      private static final String DATABASE_NAME = "walletbacking.db";
      private static final int DATABASE_VERSION = 1;

      public OpenHelper(Context context) {
         super(context, DATABASE_NAME, null, DATABASE_VERSION);
      }

      @Override
      public void onCreate(SQLiteDatabase db) {
         db.execSQL("CREATE TABLE single (id STRING PRIMARY KEY, address BLOB, addressstring STRING, archived INTEGER, blockheight INTEGER);");
         db.execSQL("CREATE TABLE bip44 (id STRING PRIMARY KEY, accountIndex INTEGER, archived INTEGER, blockheight INTEGER, lastExternalIndexWithActivity INTEGER, lastInternalIndexWithActivity INTEGER, firstMonitoredInternalIndex INTEGER, lastDiscovery);");
         db.execSQL("CREATE TABLE kv (k BLOB PRIMARY KEY, v BLOB);");
      }

      @Override
      public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
         for (UUID account : getAccountIds(db)) {
            new SqliteAccountBacking(account, db).upgradeTables();
         }
      }

   }

   private SQLiteDatabase _database;
   private Map<UUID, SqliteAccountBacking> _backings;
   private final SQLiteStatement _insertOrReplaceBip44Account;
   private final SQLiteStatement _updateBip44Account;
   private final SQLiteStatement _insertOrReplaceSingleAddressAccount;
   private final SQLiteStatement _updateSingleAddressAccount;
   private final SQLiteStatement _deleteSingleAddressAccount;
   private final SQLiteStatement _insertOrReplaceKeyValue;
   private final SQLiteStatement _deleteKeyValue;

   public SqliteWalletManagerBacking(Context context) {
      OpenHelper _openHelper = new OpenHelper(context);
      _database = _openHelper.getWritableDatabase();
      _insertOrReplaceBip44Account = _database.compileStatement("INSERT OR REPLACE INTO bip44 VALUES (?,?,?,?,?,?,?,?)");
      _insertOrReplaceSingleAddressAccount = _database.compileStatement("INSERT OR REPLACE INTO single VALUES (?,?,?,?,?)");
      _updateBip44Account = _database.compileStatement("UPDATE bip44 SET archived=?,blockheight=?,lastExternalIndexWithActivity=?,lastInternalIndexWithActivity=?,firstMonitoredInternalIndex=?,lastDiscovery=? WHERE id=?");
      _updateSingleAddressAccount = _database.compileStatement("UPDATE single SET archived=?,blockheight=? WHERE id=?");
      _deleteSingleAddressAccount = _database.compileStatement("DELETE FROM single WHERE id = ?");
      _insertOrReplaceKeyValue = _database.compileStatement("INSERT OR REPLACE INTO kv VALUES (?,?)");
      _deleteKeyValue = _database.compileStatement("DELETE FROM kv WHERE k = ?");
      _backings = new HashMap<UUID, SqliteAccountBacking>();
      for (UUID id : getAccountIds(_database)) {
         _backings.put(id, new SqliteAccountBacking(id, _database));
      }
   }

   private List<UUID> getAccountIds(SQLiteDatabase db) {
      List<UUID> ids = new ArrayList<UUID>();
      ids.addAll(getBip44AccountIds(db));
      ids.addAll(getSingleAddressAccountIds(db));
      return ids;
   }

   private List<UUID> getSingleAddressAccountIds(SQLiteDatabase db) {
      Cursor cursor = null;
      List<UUID> accounts = new ArrayList<UUID>();
      try {
         SQLiteQueryWithBlobs blobQuery = new SQLiteQueryWithBlobs(db);
         cursor = blobQuery.query(false, "single", new String[]{"id"}, null, null, null, null, null, null);
         while (cursor.moveToNext()) {
            UUID uuid = SQLiteQueryWithBlobs.uuidFromBytes(cursor.getBlob(0));
            accounts.add(uuid);
         }
         return accounts;
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in getSingleAddressAccountIds", e);
         throw new RuntimeException(e);
      } finally {
         if (cursor != null) {
            cursor.close();
         }
      }
   }

   private List<UUID> getBip44AccountIds(SQLiteDatabase db) {
      Cursor cursor = null;
      List<UUID> accounts = new ArrayList<UUID>();
      try {
         SQLiteQueryWithBlobs blobQuery = new SQLiteQueryWithBlobs(db);
         cursor = blobQuery.query(false, "bip44", new String[]{"id"}, null, null, null, null, null, null);
         while (cursor.moveToNext()) {
            UUID uuid = SQLiteQueryWithBlobs.uuidFromBytes(cursor.getBlob(0));
            accounts.add(uuid);
         }
         return accounts;
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in getBip44AccountIds", e);
         throw new RuntimeException(e);
      } finally {
         if (cursor != null) {
            cursor.close();
         }
      }
   }

   @Override
   public void beginTransaction() {
      _database.beginTransaction();
   }

   @Override
   public void setTransactionSuccessful() {
      _database.setTransactionSuccessful();
   }

   @Override
   public void endTransaction() {
      _database.endTransaction();
   }

   @Override
   public List<Bip44AccountContext> loadBip44AccountContexts() {
      List<Bip44AccountContext> list = new ArrayList<Bip44AccountContext>();
      Cursor cursor = null;
      try {
         SQLiteQueryWithBlobs blobQuery = new SQLiteQueryWithBlobs(_database);
         cursor = blobQuery.query(false, "bip44", new String[]{"id", "accountIndex", "archived", "blockheight", "lastExternalIndexWithActivity", "lastInternalIndexWithActivity", "firstMonitoredInternalIndex", "lastDiscovery"}, null, null,
               null, null, "accountIndex", null);
         while (cursor.moveToNext()) {
            UUID id = SQLiteQueryWithBlobs.uuidFromBytes(cursor.getBlob(0));
            int accountIndex = cursor.getInt(1);
            boolean isArchived = cursor.getInt(2) == 1;
            int blockHeight = cursor.getInt(3);
            int lastExternalIndexWithActivity = cursor.getInt(4);
            int lastInternalIndexWithActivity = cursor.getInt(5);
            int firstMonitoredInternalIndex = cursor.getInt(6);
            long lastDiscovery = cursor.getLong(7);
            list.add(new Bip44AccountContext(id, accountIndex, isArchived, blockHeight, lastExternalIndexWithActivity, lastInternalIndexWithActivity, firstMonitoredInternalIndex, lastDiscovery));
         }
         return list;
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in loadBip44AccountContexts", e);
         throw new RuntimeException(e);
      } finally {
         if (cursor != null) {
            cursor.close();
         }
      }
   }

   @Override
   public void createBip44AccountContext(Bip44AccountContext context) {
      _database.beginTransaction();
      try {

         // Create backing tables
         SqliteAccountBacking backing = _backings.get(context.getId());
         if (backing == null) {
            createAccountBackingTables(context.getId(), _database);
            backing = new SqliteAccountBacking(context.getId(), _database);
            _backings.put(context.getId(), backing);
         }

         // Crete context
         _insertOrReplaceBip44Account.bindBlob(1, SQLiteQueryWithBlobs.uuidToBytes(context.getId()));
         _insertOrReplaceBip44Account.bindLong(2, context.getAccountIndex());
         _insertOrReplaceBip44Account.bindLong(3, context.isArchived() ? 1 : 0);
         _insertOrReplaceBip44Account.bindLong(4, context.getBlockHeight());
         _insertOrReplaceBip44Account.bindLong(5, context.getLastExternalIndexWithActivity());
         _insertOrReplaceBip44Account.bindLong(6, context.getLastInternalIndexWithActivity());
         _insertOrReplaceBip44Account.bindLong(7, context.getFirstMonitoredInternalIndex());
         _insertOrReplaceBip44Account.bindLong(8, context.getLastDiscovery());
         _insertOrReplaceBip44Account.executeInsert();

         _database.setTransactionSuccessful();
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in createBip44AccountContext", e);
         throw new RuntimeException(e);
      } finally {
         _database.endTransaction();
      }
   }


   private void updateBip44AccountContext(Bip44AccountContext context) {
      //"UPDATE bip44 SET archived=?,blockheight=?,lastExternalIndexWithActivity=?,lastInternalIndexWithActivity=?,firstMonitoredInternalIndex=?,lastDiscovery=? WHERE id=?"
      _updateBip44Account.bindLong(1, context.isArchived() ? 1 : 0);
      _updateBip44Account.bindLong(2, context.getBlockHeight());
      _updateBip44Account.bindLong(3, context.getLastExternalIndexWithActivity());
      _updateBip44Account.bindLong(4, context.getLastInternalIndexWithActivity());
      _updateBip44Account.bindLong(5, context.getFirstMonitoredInternalIndex());
      _updateBip44Account.bindLong(6, context.getLastDiscovery());
      _updateBip44Account.bindBlob(7, SQLiteQueryWithBlobs.uuidToBytes(context.getId()));
      _updateBip44Account.execute();
   }

   @Override
   public List<SingleAddressAccountContext> loadSingleAddressAccountContexts() {
      List<SingleAddressAccountContext> list = new ArrayList<SingleAddressAccountContext>();
      Cursor cursor = null;
      try {
         SQLiteQueryWithBlobs blobQuery = new SQLiteQueryWithBlobs(_database);
         cursor = blobQuery.query(false, "single", new String[]{"id", "address", "addressstring", "archived", "blockheight"}, null, null,
               null, null, null, null);
         while (cursor.moveToNext()) {
            UUID id = SQLiteQueryWithBlobs.uuidFromBytes(cursor.getBlob(0));
            byte[] addressBytes = cursor.getBlob(1);
            String addressString = cursor.getString(2);
            Address address = new Address(addressBytes, addressString);
            boolean isArchived = cursor.getInt(3) == 1;
            int blockHeight = cursor.getInt(4);
            list.add(new SingleAddressAccountContext(id, address, isArchived, blockHeight));
         }
         return list;
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in loadSingleAddressAccountContexts", e);
         throw new RuntimeException(e);
      } finally {
         if (cursor != null) {
            cursor.close();
         }
      }
   }

   @Override
   public void createSingleAddressAccountContext(SingleAddressAccountContext context) {
      _database.beginTransaction();
      try {

         // Create backing tables
         SqliteAccountBacking backing = _backings.get(context.getId());
         if (backing == null) {
            createAccountBackingTables(context.getId(), _database);
            backing = new SqliteAccountBacking(context.getId(), _database);
            _backings.put(context.getId(), backing);
         }

         // Create context
         _insertOrReplaceSingleAddressAccount.bindBlob(1, SQLiteQueryWithBlobs.uuidToBytes(context.getId()));
         _insertOrReplaceSingleAddressAccount.bindBlob(2, context.getAddress().getAllAddressBytes());
         _insertOrReplaceSingleAddressAccount.bindString(3, context.getAddress().toString());
         _insertOrReplaceSingleAddressAccount.bindLong(4, context.isArchived() ? 1 : 0);
         _insertOrReplaceSingleAddressAccount.bindLong(5, context.getBlockHeight());
         _insertOrReplaceSingleAddressAccount.executeInsert();
         _database.setTransactionSuccessful();
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in createSingleAddressAccountContext", e);
         throw new RuntimeException(e);
      } finally {
         _database.endTransaction();
      }
   }

   private void updateSingleAddressAccountContext(SingleAddressAccountContext context) {
      // "UPDATE single SET archived=?,blockheight=? WHERE id=?"
      _updateSingleAddressAccount.bindLong(1, context.isArchived() ? 1 : 0);
      _updateSingleAddressAccount.bindLong(2, context.getBlockHeight());
      _updateSingleAddressAccount.bindBlob(3, SQLiteQueryWithBlobs.uuidToBytes(context.getId()));
      _updateSingleAddressAccount.execute();
   }

   @Override
   public void deleteSingleAddressAccountContext(UUID accountId) {
      // "DELETE FROM single WHERE id = ?"
      beginTransaction();
      try {
         SqliteAccountBacking backing = _backings.get(accountId);
         if (backing == null) {
            return;
         }
         _deleteSingleAddressAccount.bindBlob(1, SQLiteQueryWithBlobs.uuidToBytes(accountId));
         _deleteSingleAddressAccount.execute();
         backing.dropTables();
         _backings.remove(accountId);
         setTransactionSuccessful();
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in deleteSingleAddressAccountContext", e);
         throw new RuntimeException(e);
      } finally {
         endTransaction();
      }
   }

   @Override
   public Bip44AccountBacking getBip44AccountBacking(UUID accountId) {
      SqliteAccountBacking backing = _backings.get(accountId);
      Preconditions.checkNotNull(backing);
      return backing;
   }

   @Override
   public SingleAddressAccountBacking getSingleAddressAccountBacking(UUID accountId) {
      SqliteAccountBacking backing = _backings.get(accountId);
      Preconditions.checkNotNull(backing);
      return backing;
   }

   @Override
   public byte[] getValue(byte[] id) {
      Cursor cursor = null;
      try {
         SQLiteQueryWithBlobs blobQuery = new SQLiteQueryWithBlobs(_database);
         blobQuery.bindBlob(1, id);
         cursor = blobQuery.query(false, TABLE_KV, new String[]{"v"}, "k = ?", null, null, null,
               null, null);
         if (cursor.moveToNext()) {
            return cursor.getBlob(0);
         }
         return null;
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in getValue", e);
         throw new RuntimeException(e);
      } finally {
         if (cursor != null) {
            cursor.close();
         }
      }
   }

   @Override
   public void setValue(byte[] key, byte[] value) {
      try {
         _insertOrReplaceKeyValue.bindBlob(1, key);
         SQLiteQueryWithBlobs.bindBlobWithNull(_insertOrReplaceKeyValue, 2, value);
         _insertOrReplaceKeyValue.executeInsert();
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in setValue", e);
         throw new RuntimeException(e);
      }
   }

   @Override
   public void deleteValue(byte[] id) {
      try {
         _deleteKeyValue.bindBlob(1, id);
         _deleteKeyValue.execute();
      } catch (Exception e) {
         Log.e(LOG_TAG, "Exception in deleteValue", e);
         throw new RuntimeException(e);
      }
   }

   private static void createAccountBackingTables(UUID id, SQLiteDatabase db) {
      String tableSuffix = uuidToTableSuffix(id);
      db.execSQL("CREATE TABLE IF NOT EXISTS " + getUtxoTableName(tableSuffix)
            + " (outpoint BLOB PRIMARY KEY, height INTEGER, value INTEGER, isCoinbase INTEGER, script BLOB);");
      db.execSQL("CREATE TABLE IF NOT EXISTS " + getPtxoTableName(tableSuffix)
            + " (outpoint BLOB PRIMARY KEY, height INTEGER, value INTEGER, isCoinbase INTEGER, script BLOB);");
      db.execSQL("CREATE TABLE IF NOT EXISTS " + getTxTableName(tableSuffix)
            + " (id BLOB PRIMARY KEY, height INTEGER, time INTEGER, binary BLOB);");
      db.execSQL("CREATE INDEX IF NOT EXISTS heightIndex ON " + getTxTableName(tableSuffix) + " (height);");
      db.execSQL("CREATE TABLE IF NOT EXISTS " + getOutgoingTxTableName(tableSuffix)
            + " (id BLOB PRIMARY KEY, raw BLOB);");

   }

   private static String uuidToTableSuffix(UUID uuid) {
      return HexUtils.toHex(SQLiteQueryWithBlobs.uuidToBytes(uuid));
   }

   private static String getUtxoTableName(String tableSuffix) {
      return "utxo_" + tableSuffix;
   }

   private static String getPtxoTableName(String tableSuffix) {
      return "ptxo_" + tableSuffix;
   }

   private static String getTxTableName(String tableSuffix) {
      return "tx_" + tableSuffix;
   }

   private static String getOutgoingTxTableName(String tableSuffix) {
      return "outtx_" + tableSuffix;
   }


   private class SqliteAccountBacking implements Bip44AccountBacking, SingleAddressAccountBacking {

      private UUID _id;
      private final String utxoTableName;
      private final String ptxoTableName;
      private final String txTableName;
      private final String outTxTableName;
      private final SQLiteStatement _insertOrReplaceUtxo;
      private final SQLiteStatement _deleteUtxo;
      private final SQLiteStatement _insertOrReplacePtxo;
      private final SQLiteStatement _insertOrReplaceTx;
      private final SQLiteStatement _deleteTx;
      private final SQLiteStatement _insertOrReplaceOutTx;
      private final SQLiteStatement _deleteOutTx;
      private final SQLiteDatabase _db;

      private SqliteAccountBacking(UUID id, SQLiteDatabase db) {
         _id = id;
         _db = db;
         String tableSuffix = uuidToTableSuffix(id);
         utxoTableName = getUtxoTableName(tableSuffix);
         ptxoTableName = getPtxoTableName(tableSuffix);
         txTableName = getTxTableName(tableSuffix);
         outTxTableName = getOutgoingTxTableName(tableSuffix);
         _insertOrReplaceUtxo = db.compileStatement("INSERT OR REPLACE INTO " + utxoTableName + " VALUES (?,?,?,?,?)");
         _deleteUtxo = db.compileStatement("DELETE FROM " + utxoTableName + " WHERE outpoint = ?");
         _insertOrReplacePtxo = db.compileStatement("INSERT OR REPLACE INTO " + ptxoTableName + " VALUES (?,?,?,?,?)");
         _insertOrReplaceTx = db.compileStatement("INSERT OR REPLACE INTO " + txTableName + " VALUES (?,?,?,?)");
         _deleteTx = db.compileStatement("DELETE FROM " + txTableName + " WHERE id = ?");
         _insertOrReplaceOutTx = db.compileStatement("INSERT OR REPLACE INTO " + outTxTableName + " VALUES (?,?)");
         _deleteOutTx = db.compileStatement("DELETE FROM " + outTxTableName + " WHERE id = ?");
      }

      private void dropTables() {
         String tableSuffix = uuidToTableSuffix(_id);
         _db.execSQL("DROP TABLE IF EXISTS " + getUtxoTableName(tableSuffix));
         _db.execSQL("DROP TABLE IF EXISTS " + getPtxoTableName(tableSuffix));
         _db.execSQL("DROP TABLE IF EXISTS " + getTxTableName(tableSuffix));
         _db.execSQL("DROP TABLE IF EXISTS " + getOutgoingTxTableName(tableSuffix));
      }

      @Override
      public void beginTransaction() {
         SqliteWalletManagerBacking.this.beginTransaction();
      }

      @Override
      public void setTransactionSuccessful() {
         SqliteWalletManagerBacking.this.setTransactionSuccessful();
      }

      @Override
      public void endTransaction() {
         SqliteWalletManagerBacking.this.endTransaction();
      }

      @Override
      public void clear() {
         _db.execSQL("DELETE FROM " + utxoTableName);
         _db.execSQL("DELETE FROM " + ptxoTableName);
         _db.execSQL("DELETE FROM " + txTableName);
         _db.execSQL("DELETE FROM " + outTxTableName);
      }


      private void upgradeTables() {
         dropTables();
         createAccountBackingTables(_id, _db);
      }

      @Override
      public synchronized void putUnspentOutput(TransactionOutputEx output) {
         try {
            _insertOrReplaceUtxo.bindBlob(1, SQLiteQueryWithBlobs.outPointToBytes(output.outPoint));
            _insertOrReplaceUtxo.bindLong(2, output.height);
            _insertOrReplaceUtxo.bindLong(3, output.value);
            _insertOrReplaceUtxo.bindLong(4, output.isCoinBase ? 1 : 0);
            _insertOrReplaceUtxo.bindBlob(5, output.script);
            _insertOrReplaceUtxo.executeInsert();
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in putUnspentOutput", e);
            throw new RuntimeException(e);
         }
      }

      @Override
      public Collection<TransactionOutputEx> getAllUnspentOutputs() {
         Cursor cursor = null;
         List<TransactionOutputEx> list = new LinkedList<TransactionOutputEx>();
         try {
            SQLiteQueryWithBlobs blobQuery = new SQLiteQueryWithBlobs(_db);
            cursor = blobQuery.query(false, utxoTableName, new String[]{"outpoint", "height", "value", "isCoinbase",
                  "script"}, null, null, null, null, null, null);
            while (cursor.moveToNext()) {
               TransactionOutputEx tex = new TransactionOutputEx(SQLiteQueryWithBlobs.outPointFromBytes(cursor
                     .getBlob(0)), cursor.getInt(1), cursor.getLong(2), cursor.getBlob(4), cursor.getInt(3) != 0);
               list.add(tex);
            }
            return list;
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in getAllUnspentOutputs", e);
            throw new RuntimeException(e);
         } finally {
            if (cursor != null) {
               cursor.close();
            }
         }
      }

      @Override
      public TransactionOutputEx getUnspentOutput(OutPoint outPoint) {
         Cursor cursor = null;
         try {
            SQLiteQueryWithBlobs blobQuery = new SQLiteQueryWithBlobs(_db);
            blobQuery.bindBlob(1, SQLiteQueryWithBlobs.outPointToBytes(outPoint));
            cursor = blobQuery.query(false, utxoTableName, new String[]{"height", "value", "isCoinbase", "script"},
                  "outpoint = ?", null, null, null, null, null);
            if (cursor.moveToNext()) {
               return new TransactionOutputEx(outPoint, cursor.getInt(0), cursor.getLong(1),
                     cursor.getBlob(3), cursor.getInt(2) != 0);
            }
            return null;
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in getUnspentOutput", e);
            throw new RuntimeException(e);
         } finally {
            if (cursor != null) {
               cursor.close();
            }
         }
      }

      @Override
      public void deleteUnspentOutput(OutPoint outPoint) {
         _deleteUtxo.bindBlob(1, SQLiteQueryWithBlobs.outPointToBytes(outPoint));
         _deleteUtxo.execute();
      }

      @Override
      public void putParentTransactionOutput(TransactionOutputEx output) {
         try {
            _insertOrReplacePtxo.bindBlob(1, SQLiteQueryWithBlobs.outPointToBytes(output.outPoint));
            _insertOrReplacePtxo.bindLong(2, output.height);
            _insertOrReplacePtxo.bindLong(3, output.value);
            _insertOrReplacePtxo.bindLong(4, output.isCoinBase ? 1 : 0);
            _insertOrReplacePtxo.bindBlob(5, output.script);
            _insertOrReplacePtxo.executeInsert();
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in putParentTransactoinOutput", e);
            throw new RuntimeException(e);
         }
      }

      @Override
      public TransactionOutputEx getParentTransactionOutput(OutPoint outPoint) {
         Cursor cursor = null;
         try {
            SQLiteQueryWithBlobs blobQuery = new SQLiteQueryWithBlobs(_db);
            blobQuery.bindBlob(1, SQLiteQueryWithBlobs.outPointToBytes(outPoint));
            cursor = blobQuery.query(false, ptxoTableName, new String[]{"height", "value", "isCoinbase", "script"},
                  "outpoint = ?", null, null, null, null, null);
            if (cursor.moveToNext()) {
               return new TransactionOutputEx(outPoint, cursor.getInt(0), cursor.getLong(1),
                     cursor.getBlob(3), cursor.getInt(2) != 0);
            }
            return null;
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in getParentTransactoinOutput", e);
            throw new RuntimeException(e);
         } finally {
            if (cursor != null) {
               cursor.close();
            }
         }
      }

      @Override
      public boolean hasParentTransactionOutput(OutPoint outPoint) {
         Cursor cursor = null;
         try {
            SQLiteQueryWithBlobs blobQuery = new SQLiteQueryWithBlobs(_db);
            blobQuery.bindBlob(1, SQLiteQueryWithBlobs.outPointToBytes(outPoint));
            cursor = blobQuery.query(false, ptxoTableName, new String[]{"height"}, "outpoint = ?", null, null, null,
                  null, null);
            return cursor.moveToNext();
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in hasParentTransactionOutput", e);
            throw new RuntimeException(e);
         } finally {
            if (cursor != null) {
               cursor.close();
            }
         }
      }

      @Override
      public void putTransaction(TransactionEx tx) {
         try {
            _insertOrReplaceTx.bindBlob(1, tx.txid.getBytes());
            _insertOrReplaceTx.bindLong(2, tx.height == -1 ? Integer.MAX_VALUE : tx.height);
            _insertOrReplaceTx.bindLong(3, tx.time);
            _insertOrReplaceTx.bindBlob(4, tx.binary);
            _insertOrReplaceTx.executeInsert();
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in putTransaction", e);
            throw new RuntimeException(e);
         }
      }

      @Override
      public TransactionEx getTransaction(Sha256Hash hash) {
         Cursor cursor = null;
         try {
            SQLiteQueryWithBlobs blobQuery = new SQLiteQueryWithBlobs(_db);
            blobQuery.bindBlob(1, hash.getBytes());
            cursor = blobQuery.query(false, txTableName, new String[]{"height", "time", "binary"}, "id = ?", null,
                  null, null, null, null);
            if (cursor.moveToNext()) {
               int height = cursor.getInt(0);
               if (height == Integer.MAX_VALUE) {
                  height = -1;
               }
               return new TransactionEx(hash, height, cursor.getInt(1), cursor.getBlob(2));
            }
            return null;
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in getUnspentOutput", e);
            throw new RuntimeException(e);
         } finally {
            if (cursor != null) {
               cursor.close();
            }
         }
      }

      @Override
      public void deleteTransaction(Sha256Hash hash) {
         _deleteTx.bindBlob(1, hash.getBytes());
         _deleteTx.execute();
      }

      @Override
      public boolean hasTransaction(Sha256Hash hash) {
         Cursor cursor = null;
         try {
            SQLiteQueryWithBlobs blobQuery = new SQLiteQueryWithBlobs(_db);
            blobQuery.bindBlob(1, hash.getBytes());
            cursor = blobQuery.query(false, txTableName, new String[]{"height"}, "id = ?", null, null, null, null,
                  null);
            return cursor.moveToNext();
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in hasTransaction", e);
            throw new RuntimeException(e);
         } finally {
            if (cursor != null) {
               cursor.close();
            }
         }
      }

      @Override
      public Collection<TransactionEx> getUnconfirmedTransactions() {
         Cursor cursor = null;
         List<TransactionEx> list = new LinkedList<TransactionEx>();
         try {
            // 2147483647 == Integer.MAX_VALUE
            cursor = _db.rawQuery("SELECT id, time, binary FROM " + txTableName + " WHERE height = 2147483647",
                  new String[]{});
            while (cursor.moveToNext()) {
               TransactionEx tex = new TransactionEx(new Sha256Hash(cursor.getBlob(0)), -1, cursor.getInt(1),
                     cursor.getBlob(2));
               list.add(tex);
            }
            return list;
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in getUnconfirmedTransactions", e);
            throw new RuntimeException(e);
         } finally {
            if (cursor != null) {
               cursor.close();
            }
         }
      }

      @Override
      public Collection<TransactionEx> getYoungTransactions(int maxConfirmations, int blockChainHeight) {
         int maxHeight = blockChainHeight - maxConfirmations + 1;
         Cursor cursor = null;
         List<TransactionEx> list = new LinkedList<TransactionEx>();
         try {
            cursor = _db.rawQuery("SELECT id, height, time, binary FROM " + txTableName + " WHERE height >= ? ",
                  new String[]{Integer.toString(maxHeight)});
            while (cursor.moveToNext()) {
               int height = cursor.getInt(1);
               if (height == Integer.MAX_VALUE) {
                  height = -1;
               }
               TransactionEx tex = new TransactionEx(new Sha256Hash(cursor.getBlob(0)), height, cursor.getInt(2),
                     cursor.getBlob(3));
               list.add(tex);
            }
            return list;
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in getUnconfirmedTransactions", e);
            throw new RuntimeException(e);
         } finally {
            if (cursor != null) {
               cursor.close();
            }
         }
      }

      @Override
      public void putOutgoingTransaction(Sha256Hash txid, byte[] rawTransaction) {
         try {
            _insertOrReplaceOutTx.bindBlob(1, txid.getBytes());
            _insertOrReplaceOutTx.bindBlob(2, rawTransaction);
            _insertOrReplaceOutTx.executeInsert();
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in putOutgoingTransaction", e);
            throw new RuntimeException(e);
         }
      }

      @Override
      public List<byte[]> getOutgoingTransactions() {
         Cursor cursor = null;
         List<byte[]> list = new LinkedList<byte[]>();
         try {
            cursor = _db.rawQuery("SELECT raw FROM " + outTxTableName, new String[]{});
            while (cursor.moveToNext()) {
               list.add(cursor.getBlob(0));
            }
            return list;
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in getOutgoingTransactions", e);
            throw new RuntimeException(e);
         } finally {
            if (cursor != null) {
               cursor.close();
            }
         }
      }

      @Override
      public void removeOutgoingTransaction(Sha256Hash txid) {
         _deleteOutTx.bindBlob(1, txid.getBytes());
         _deleteOutTx.execute();
      }

      @Override
      public boolean isOutgoingTransaction(Sha256Hash txid) {
         Cursor cursor = null;
         try {
            SQLiteQueryWithBlobs blobQuery = new SQLiteQueryWithBlobs(_db);
            blobQuery.bindBlob(1, txid.getBytes());
            cursor = blobQuery.query(false, outTxTableName, new String[]{}, "id = ?", null, null, null, null,
                  null);
            return cursor.moveToNext();
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in hasTransaction", e);
            throw new RuntimeException(e);
         } finally {
            if (cursor != null) {
               cursor.close();
            }
         }
      }

      @Override
      public List<TransactionEx> getTransactionHistory(int offset, int limit) {
         Cursor cursor = null;
         List<TransactionEx> list = new LinkedList<TransactionEx>();
         try {
            cursor = _db.rawQuery("SELECT id, height, time, binary FROM " + txTableName
                        + " ORDER BY height desc limit ? offset ?",
                  new String[]{Integer.toString(limit), Integer.toString(offset)});
            while (cursor.moveToNext()) {
               TransactionEx tex = new TransactionEx(new Sha256Hash(cursor.getBlob(0)), cursor.getInt(1),
                     cursor.getInt(2), cursor.getBlob(3));
               list.add(tex);
            }
            return list;
         } catch (Exception e) {
            Log.e(LOG_TAG, "Exception in getTransactionHistory", e);
            throw new RuntimeException(e);
         } finally {
            if (cursor != null) {
               cursor.close();
            }
         }
      }

      @Override
      public void updateAccountContext(Bip44AccountContext context) {
         updateBip44AccountContext(context);
      }

      @Override
      public void updateAccountContext(SingleAddressAccountContext context) {
         updateSingleAddressAccountContext(context);
      }
   }

}
