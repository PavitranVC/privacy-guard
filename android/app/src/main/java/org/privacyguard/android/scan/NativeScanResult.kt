package org.privacyguard.android.scan

import android.os.Parcel
import android.os.Parcelable

data class NativeScanResult(
    val features: FloatArray,
    val riskScore: Double,
    val matchedTrackers: List<String>,
    val entropy: Double,
    val hasDynamicLoading: Boolean,
    val frameworkType: String
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.createFloatArray() ?: FloatArray(0),
        parcel.readDouble(),
        parcel.createStringArrayList() ?: emptyList(),
        parcel.readDouble(),
        parcel.readByte() != 0.toByte(),
        parcel.readString() ?: "UNKNOWN"
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeFloatArray(features)
        parcel.writeDouble(riskScore)
        parcel.writeStringList(matchedTrackers)
        parcel.writeDouble(entropy)
        parcel.writeByte(if (hasDynamicLoading) 1 else 0)
        parcel.writeString(frameworkType)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<NativeScanResult> {
        override fun createFromParcel(parcel: Parcel): NativeScanResult = NativeScanResult(parcel)
        override fun newArray(size: Int): Array<NativeScanResult?> = arrayOfNulls(size)
    }
}
