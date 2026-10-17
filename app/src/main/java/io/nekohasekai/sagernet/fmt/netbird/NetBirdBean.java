package io.nekohasekai.sagernet.fmt.netbird;

import androidx.annotation.NonNull;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import org.jetbrains.annotations.NotNull;
import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

/** Enrollment settings only. Private peer identity lives in app-private storage. */
public class NetBirdBean extends AbstractBean {
    public String managementUrl;
    public String setupKey;
    public String hostname;
    public String dnsDomains;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (managementUrl == null) managementUrl = "https://api.netbird.io:443";
        if (setupKey == null) setupKey = "";
        if (hostname == null) hostname = "nekobox-android";
        if (dnsDomains == null) dnsDomains = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(managementUrl);
        output.writeString(setupKey);
        output.writeString(hostname);
        output.writeString(dnsDomains);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        input.readInt();
        super.deserialize(input);
        managementUrl = input.readString();
        setupKey = input.readString();
        hostname = input.readString();
        dnsDomains = input.readString();
    }

    @Override public String displayAddress() { return managementUrl; }
    @Override public boolean canTCPing() { return false; }
    @Override public boolean canICMPing() { return false; }
    @Override public boolean canMapping() { return false; }
    @NotNull @Override public String getHash() { return buildTypedHash("netbird"); }
    @NotNull @Override public String toString() { return "NetBirdBean [credentials redacted]"; }

    @NotNull @Override
    public NetBirdBean clone() {
        return KryoConverters.deserialize(new NetBirdBean(), KryoConverters.serialize(this));
    }

    public static final Creator<NetBirdBean> CREATOR = new CREATOR<NetBirdBean>() {
        @NonNull @Override public NetBirdBean newInstance() { return new NetBirdBean(); }
        @Override public NetBirdBean[] newArray(int size) { return new NetBirdBean[size]; }
    };
}
