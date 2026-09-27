/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.swarm

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.prop
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.signal.registration.RegistrationFlowEvent
import org.signal.registration.RegistrationFlowState
import org.signal.registration.RegistrationRepository
import org.signal.registration.RegistrationRoute
import org.signal.registration.screens.welcome.WelcomeScreenEvents
import org.signal.registration.screens.welcome.WelcomeScreenState
import org.signal.registration.screens.welcome.WelcomeScreenViewModel

/**
 * SWARM addition (wallet sign-in): with wallet sign-in on, the welcome screen leads to the wallet screen and offers
 * none of upstream's backup-restore or transfer paths. (With it off, upstream's own WelcomeScreenViewModelTest
 * still holds, unchanged.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwarmWelcomeScreenTest {

  private val testDispatcher = UnconfinedTestDispatcher()
  private val events = mutableListOf<RegistrationFlowEvent>()
  private val states = mutableListOf<WelcomeScreenState>()

  private fun viewModel(hasPermissions: Boolean): WelcomeScreenViewModel {
    val repository = mockk<RegistrationRepository>(relaxed = true)
    every { repository.isLinkAndSyncAvailable } returns false
    every { repository.isSwarmWalletSignIn } returns true
    return WelcomeScreenViewModel(repository, MutableStateFlow(RegistrationFlowState()), { events += it }, hasPermissions = { hasPermissions }, getRequiredLinkedDevicePermission = { null })
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `Continue goes to the wallet screen`() {
    viewModel(hasPermissions = true).applyEvent(WelcomeScreenState(), WelcomeScreenEvents.Continue, { events += it }) { states += it }

    assertThat(events.last())
      .isInstanceOf<RegistrationFlowEvent.NavigateToScreen>()
      .prop(RegistrationFlowEvent.NavigateToScreen::route)
      .isEqualTo(RegistrationRoute.SwarmWallet)
  }

  @Test
  fun `Continue goes to the wallet screen through the permissions screen when permissions are missing`() {
    viewModel(hasPermissions = false).applyEvent(WelcomeScreenState(), WelcomeScreenEvents.Continue, { events += it }) { states += it }

    assertThat(events.last())
      .isInstanceOf<RegistrationFlowEvent.NavigateToScreen>()
      .prop(RegistrationFlowEvent.NavigateToScreen::route)
      .isEqualTo(RegistrationRoute.Permissions(nextRoute = RegistrationRoute.SwarmWallet))
  }

  @Test
  fun `restore or transfer is never offered`() {
    viewModel(hasPermissions = true).applyEvent(
      WelcomeScreenState(showRestoreOrTransfer = true),
      WelcomeScreenEvents.ParentStateChanged(RegistrationFlowState(isRestoringNavigationState = false, preExistingRegistrationData = null)),
      { events += it }
    ) { states += it }

    assertThat(states.last().showRestoreOrTransfer).isFalse()
  }
}
