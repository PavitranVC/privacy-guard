package org.privacyguard.android.scan

import android.os.Parcel
import android.os.Parcelable

data class NativeScanResult(
    val scanStatus: Int,
    val features: FloatArray,
    val riskScore: Double,
    val maxConfidence: Double,
    val matchedTrackers: List<String>,
    val entropy: Double,
    val hasDynamicLoading: Boolean,
    val frameworkType: String
) : Parcelable {

    constructor(parcel: Parcel) : this(
        scanStatus = parcel.readInt(),
        features = parcel.createFloatArray() ?: FloatArray(0),
        riskScore = parcel.readDouble(),
        maxConfidence = parcel.readDouble(),
        matchedTrackers = parcel.createStringArrayList() ?: emptyList(),
        entropy = parcel.readDouble(),
        hasDynamicLoading = parcel.readByte() != 0.toByte(),
        frameworkType = parcel.readString() ?: "UNKNOWN"
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(scanStatus)
        parcel.writeFloatArray(features)
        parcel.writeDouble(riskScore)
        parcel.writeDouble(maxConfidence)
        parcel.writeStringList(matchedTrackers)
        parcel.writeDouble(entropy)
        parcel.writeByte(if (hasDynamicLoading) 1 else 0)
        parcel.writeString(frameworkType)
    }

    override fun describeContents(): Int = 0

    companion object {
        const val STATUS_OK = 0
        const val STATUS_PARSE_FAILED = 1
        const val STATUS_QUARANTINED = 2
        const val STATUS_INIT_FAILED = 3

        @JvmField
        val CREATOR: Parcelable.Creator<NativeScanResult> = object : Parcelable.Creator<NativeScanResult> {
            override fun createFromParcel(parcel: Parcel): NativeScanResult = NativeScanResult(parcel)
            override fun newArray(size: Int): Array<NativeScanResult?> = arrayOfNulls(size)
        }
    }
}
