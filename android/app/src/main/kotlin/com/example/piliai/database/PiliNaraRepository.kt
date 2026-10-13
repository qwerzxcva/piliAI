package com.example.piliai.database

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一 Repository 层
 * 封装所有数据访问操作
 */
class PiliNaraRepository(private val context: Context) {
    
    private val database: PiliNaraDatabase = PiliNaraDatabase.getDatabase(context)
    
    // ========== UserInfo ==========
    suspend fun getUserInfo(): UserInfoEntity? = withContext(Dispatchers.IO) {
        database.userInfoDao().getByKey("userInfoCache")
    }
    
    suspend fun saveUserInfo(entity: UserInfoEntity) = withContext(Dispatchers.IO) {
        database.userInfoDao().upsert(entity)
    }
    
    // ========== LoginAccount ==========
    suspend fun getLoginAccount(mid: String): LoginAccountEntity? = withContext(Dispatchers.IO) {
        database.loginAccountDao().getByMid(mid)
    }
    
    suspend fun getAllLoginAccounts(): List<LoginAccountEntity> = withContext(Dispatchers.IO) {
        database.loginAccountDao().getAll()
    }
    
    suspend fun saveLoginAccount(entity: LoginAccountEntity) = withContext(Dispatchers.IO) {
        database.loginAccountDao().upsert(entity)
    }
    
    suspend fun deleteAllLoginAccounts() = withContext(Dispatchers.IO) {
        database.loginAccountDao().deleteAll()
    }
    
    // ========== Setting ==========
    suspend fun getSetting(key: String): SettingEntity? = withContext(Dispatchers.IO) {
        database.settingDao().getByKey(key)
    }
    
    suspend fun getAllSettings(): List<SettingEntity> = withContext(Dispatchers.IO) {
        database.settingDao().getAll()
    }
    
    suspend fun saveSetting(key: String, value: Any?) = withContext(Dispatchers.IO) {
        val entity = when (value) {
            is String -> SettingEntity(key = key, valueString = value, valueType = "string")
            is Int -> SettingEntity(key = key, valueInt = value, valueType = "int")
            is Boolean -> SettingEntity(key = key, valueBool = value, valueType = "bool")
            is Double -> SettingEntity(key = key, valueDouble = value, valueType = "double")
            else -> SettingEntity(key = key, valueJson = value?.toString(), valueType = "json")
        }
        database.settingDao().upsert(entity)
    }
    
    suspend fun deleteSettings(keys: List<String>) = withContext(Dispatchers.IO) {
        database.settingDao().deleteByKeyList(keys)
    }
    
    suspend fun clearAllSettings() = withContext(Dispatchers.IO) {
        database.settingDao().deleteAll()
    }
    
    // ========== LocalCache ==========
    suspend fun getLocalCache(key: String): LocalCacheEntity? = withContext(Dispatchers.IO) {
        database.localCacheDao().getByKey(key)
    }
    
    suspend fun saveLocalCache(key: String, value: Any?) = withContext(Dispatchers.IO) {
        val entity = when (value) {
            is String -> LocalCacheEntity(key = key, valueString = value, valueType = "string")
            is Int -> LocalCacheEntity(key = key, valueInt = value, valueType = "int")
            is Boolean -> LocalCacheEntity(key = key, valueBool = value, valueType = "bool")
            else -> LocalCacheEntity(key = key, valueJson = value?.toString(), valueType = "json")
        }
        database.localCacheDao().upsert(entity)
    }
    
    suspend fun clearAllLocalCache() = withContext(Dispatchers.IO) {
        database.localCacheDao().deleteAll()
    }
    
    // ========== DanmakuFilterRule ==========
    suspend fun getDanmakuFilterRule(): DanmakuFilterRuleEntity? = withContext(Dispatchers.IO) {
        database.danmakuFilterRuleDao().getByKey("danmakuFilterRules")
    }
    
    suspend fun saveDanmakuFilterRule(entity: DanmakuFilterRuleEntity) = withContext(Dispatchers.IO) {
        database.danmakuFilterRuleDao().upsert(entity)
    }
    
    // ========== TodayWatchFeedback ==========
    suspend fun getTodayWatchFeedback(): TodayWatchFeedbackEntity? = withContext(Dispatchers.IO) {
        database.todayWatchFeedbackDao().getByKey("today_watch_feedback_v1")
    }
    
    suspend fun saveTodayWatchFeedback(entity: TodayWatchFeedbackEntity) = withContext(Dispatchers.IO) {
        database.todayWatchFeedbackDao().upsert(entity)
    }
}
