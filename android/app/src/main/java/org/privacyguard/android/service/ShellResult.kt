package org.privacyguard.android.service

import android.os.Parcel
import android.os.Parcelable

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readString() ?: "",
        parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(exitCode)
        parcel.writeString(stdout)
        parcel.writeString(stderr)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ShellResult> {
        override fun createFromParcel(parcel: Parcel): ShellResult = ShellResult(parcel)
        override fun newArray(size: Int): Array<ShellResult?> = arrayOfNulls(size)
    }
}
