package org.witness.proofmode.camera.fragments

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.witness.proofmode.camera.db.CertificationDatabase
import org.witness.proofmode.camera.db.CertificationRecord
import org.witness.proofmode.camera.network.CertificationService

sealed class ExtendState {
    object Idle : ExtendState()
    data class Loading(val authId: String) : ExtendState()
    data class Success(val authId: String) : ExtendState()
    data class Failure(val authId: String, val error: String) : ExtendState()
}

class MyCertificationsViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = CertificationDatabase.get(application).certificationDao()

    val certifications: StateFlow<List<CertificationRecord>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _extendState = MutableStateFlow<ExtendState>(ExtendState.Idle)
    val extendState: StateFlow<ExtendState> = _extendState

    fun freeExtend(authId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _extendState.value = ExtendState.Loading(authId)
            val newExpiresAtMs = CertificationService().freeExtend(authId)
            if (newExpiresAtMs != null) {
                dao.updateExpiry(authId, newExpiresAtMs)
                _extendState.value = ExtendState.Success(authId)
            } else {
                _extendState.value = ExtendState.Failure(authId, "연장에 실패했습니다. 네트워크 상태를 확인해주세요.")
            }
        }
    }

    fun resetExtendState() { _extendState.value = ExtendState.Idle }

    fun delete(record: CertificationRecord) {
        viewModelScope.launch(Dispatchers.IO) { dao.delete(record) }
    }
}
