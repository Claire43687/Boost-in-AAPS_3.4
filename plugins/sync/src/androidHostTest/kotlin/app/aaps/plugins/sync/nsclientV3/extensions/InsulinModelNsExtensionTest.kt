package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.InsulinModel
import app.aaps.core.data.model.PS
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSEffectiveProfileSwitch
import app.aaps.core.nssdk.localmodel.treatment.NSProfileSwitch
import app.aaps.core.nssdk.mapper.convertToRemoteAndBack
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The insulin curve model travels with the ICfg on each Nightscout treatment that carries one: boluses,
 * profile switches and effective profile switches. Oref is uploaded without the key, so it is unchanged.
 */
internal class InsulinModelNsExtensionTest : TestBaseWithProfile() {

    private val lyumjev = ICfg("Lyumjev PD 45m 8h U200", peak = 45, dia = 8.0, concentration = 2.0, model = InsulinModel.LYUMJEV_PD)
    private val fallback = ICfg("Fallback", 360 * 60 * 1000, 60 * 60 * 1000)

    @Test
    fun bolusKeepsTheModel() {
        val bolus = BS(timestamp = 10000, amount = 1.0, type = BS.Type.NORMAL, iCfg = lyumjev)
        val ns = bolus.toNSBolus()
        assertThat(ns.iCfg?.insulinModel).isEqualTo("LYUMJEV_PD")
        val back = (ns.convertToRemoteAndBack() as NSBolus).toBolus(fallback)
        assertThat(back.iCfg).isEqualTo(lyumjev)
    }

    @Test
    fun orefBolusIsUploadedWithoutTheKey() {
        val oref = ICfg("Rapid", peak = 75, dia = 8.0, concentration = 1.0)
        val ns = BS(timestamp = 10000, amount = 1.0, type = BS.Type.NORMAL, iCfg = oref).toNSBolus()
        assertThat(ns.iCfg?.insulinModel).isNull()
        assertThat((ns.convertToRemoteAndBack() as NSBolus).toBolus(fallback).iCfg.model).isEqualTo(InsulinModel.OREF)
    }

    @Test
    fun profileSwitchKeepsTheModel() {
        val ps = PS(
            timestamp = 10000, basalBlocks = effectiveProfile.basalBlocks, isfBlocks = effectiveProfile.isfBlocks,
            icBlocks = effectiveProfile.icBlocks, targetBlocks = effectiveProfile.targetBlocks, glucoseUnit = effectiveProfile.units,
            profileName = "SomeProfile", timeshift = 0, percentage = 100, duration = 0, iCfg = lyumjev
        )
        val back = (ps.toNSProfileSwitch(dateUtil, decimalFormatter).convertToRemoteAndBack() as NSProfileSwitch)
            .toProfileSwitch(profileRepository, dateUtil, fallback)!!
        assertThat(back.iCfg).isEqualTo(lyumjev)
    }

    @Test
    fun effectiveProfileSwitchKeepsTheModel() {
        val eps = EPS(
            timestamp = 10000, basalBlocks = effectiveProfile.basalBlocks, isfBlocks = effectiveProfile.isfBlocks,
            icBlocks = effectiveProfile.icBlocks, targetBlocks = effectiveProfile.targetBlocks, glucoseUnit = effectiveProfile.units,
            originalProfileName = "SomeProfile", originalCustomizedName = "SomeProfile", originalTimeshift = 0,
            originalPercentage = 100, originalDuration = 0, originalEnd = 0, iCfg = lyumjev
        )
        val back = (eps.toNSEffectiveProfileSwitch(dateUtil).convertToRemoteAndBack() as NSEffectiveProfileSwitch)
            .toEffectiveProfileSwitch(dateUtil, fallback)!!
        assertThat(back.iCfg).isEqualTo(lyumjev)
    }
}
