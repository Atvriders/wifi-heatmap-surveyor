package com.atvriders.wifiheatmap.core.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table-driven matrix over [PermissionsGate.evaluate]: every scenario is run at
 * API levels 26, 28, 29, 31, 33 and 35. Expectations are expressed per-SDK so
 * the coarse-only split (sufficient on 26..28, insufficient on 29+) is explicit.
 */
class PermissionsGateTest {

    private val sdks = intArrayOf(26, 28, 29, 31, 33, 35)

    /** One row of the matrix: a device shape and the expected step per API level. */
    private data class Case(
        val name: String,
        val fine: Boolean,
        val coarse: Boolean,
        val services: Boolean,
        val wifi: Boolean,
        val expected: (sdkInt: Int) -> OnboardingStep,
    )

    private val cases = listOf(
        Case(
            name = "no permission at all",
            fine = false, coarse = false, services = true, wifi = true,
            expected = { OnboardingStep.NEED_LOCATION_PERMISSION },
        ),
        Case(
            name = "no permission and everything else also off (permission still wins)",
            fine = false, coarse = false, services = false, wifi = false,
            expected = { OnboardingStep.NEED_LOCATION_PERMISSION },
        ),
        Case(
            name = "coarse-only",
            fine = false, coarse = true, services = true, wifi = true,
            expected = { sdk ->
                if (sdk >= 29) OnboardingStep.NEED_PRECISE_LOCATION else OnboardingStep.READY
            },
        ),
        Case(
            name = "coarse-only with services off (precise outranks services on 29+)",
            fine = false, coarse = true, services = false, wifi = true,
            expected = { sdk ->
                if (sdk >= 29) OnboardingStep.NEED_PRECISE_LOCATION
                else OnboardingStep.NEED_LOCATION_SERVICES
            },
        ),
        Case(
            name = "coarse-only with wifi off (26..28 falls through to wifi)",
            fine = false, coarse = true, services = true, wifi = false,
            expected = { sdk ->
                if (sdk >= 29) OnboardingStep.NEED_PRECISE_LOCATION else OnboardingStep.NEED_WIFI_ON
            },
        ),
        Case(
            name = "fine granted but location services off",
            fine = true, coarse = true, services = false, wifi = true,
            expected = { OnboardingStep.NEED_LOCATION_SERVICES },
        ),
        Case(
            name = "fine granted, services off and wifi off (services wins)",
            fine = true, coarse = true, services = false, wifi = false,
            expected = { OnboardingStep.NEED_LOCATION_SERVICES },
        ),
        Case(
            name = "services on but wifi off",
            fine = true, coarse = true, services = true, wifi = false,
            expected = { OnboardingStep.NEED_WIFI_ON },
        ),
        Case(
            name = "fine-only (no coarse), everything else on",
            fine = true, coarse = false, services = true, wifi = true,
            expected = { OnboardingStep.READY },
        ),
        Case(
            name = "everything on",
            fine = true, coarse = true, services = true, wifi = true,
            expected = { OnboardingStep.READY },
        ),
    )

    @Test
    fun fullMatrixAcrossSdkLevels() {
        for (case in cases) {
            for (sdk in sdks) {
                val state = DeviceState(
                    sdkInt = sdk,
                    fineGranted = case.fine,
                    coarseGranted = case.coarse,
                    locationServicesOn = case.services,
                    wifiOn = case.wifi,
                )
                assertEquals(
                    "case '${case.name}' at sdk $sdk (state=$state)",
                    case.expected(sdk),
                    PermissionsGate.evaluate(state),
                )
            }
        }
    }

    @Test
    fun coarseOnlyBoundaryIsExactlyApi29() {
        val coarseOnly = { sdk: Int ->
            DeviceState(
                sdkInt = sdk,
                fineGranted = false,
                coarseGranted = true,
                locationServicesOn = true,
                wifiOn = true,
            )
        }
        assertEquals(OnboardingStep.READY, PermissionsGate.evaluate(coarseOnly(28)))
        assertEquals(OnboardingStep.NEED_PRECISE_LOCATION, PermissionsGate.evaluate(coarseOnly(29)))
    }
}
