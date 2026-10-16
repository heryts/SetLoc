package com.heryts.setloc.utils.ext

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun ViewModel.onIO(body: suspend () -> Unit): Job = viewModelScope.launch(Dispatchers.IO) { body() }
