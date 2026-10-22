package io.nekohasekai.sagernet.fmt

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MasterDnsParcelDeviceTest {
    @Test fun parcelRoundTripUsesConcreteCreator() {
        val source = MasterDnsVPNBean().apply {
            initializeDefaultValues()
            domains = "tunnel.example.invalid"
            resolvers = "192.0.2.53"
            packetDuplicationCount = 3
            name = "synthetic parcel test"
        }
        val parcel = Parcel.obtain()
        try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = MasterDnsVPNBean.CREATOR.createFromParcel(parcel)
            assertEquals(source.domains, restored.domains)
            assertEquals(source.resolvers, restored.resolvers)
            assertEquals(source.packetDuplicationCount, restored.packetDuplicationCount)
            assertEquals(source.name, restored.name)
            assertArrayEquals(KryoConverters.serialize(source), KryoConverters.serialize(restored))
            assertEquals(2, MasterDnsVPNBean.CREATOR.newArray(2).size)
        } finally {
            parcel.recycle()
        }
    }
}
