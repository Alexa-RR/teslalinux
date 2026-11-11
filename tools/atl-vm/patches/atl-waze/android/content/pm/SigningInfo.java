/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;

import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * Information pertaining to the signing certificates used to sign a package (AOSP).
 *
 * <p>teslalinux: AOSP wraps a SigningDetails; the same data is held here directly: the signers
 * PackageParser.collectCertificates read from the installed APK (APK Signature Scheme v3, v2 or
 * the JAR signature), the scheme version, and the past certificates of a v3 rotation lineage.
 */
public final class SigningInfo implements Parcelable {
    /** AOSP SigningDetails.SignatureSchemeVersion */
    static final int SCHEME_UNKNOWN = 0;

    private final Signature[] mSignatures;
    private final Signature[] mPastSigningCertificates;
    private final int mSchemeVersion;

    /** AOSP: signing info of an unknown signer. */
    public SigningInfo() {
        this(null, null, SCHEME_UNKNOWN);
    }

    /** @hide the signers, past certificates (oldest first, may be null) and scheme version */
    public SigningInfo(Signature[] signatures, Signature[] pastSigningCertificates, int schemeVersion) {
        mSignatures = signatures;
        mPastSigningCertificates = pastSigningCertificates;
        mSchemeVersion = schemeVersion;
    }

    /** AOSP: a copy. */
    public SigningInfo(SigningInfo orig) {
        mSignatures = orig.mSignatures != null ? orig.mSignatures.clone() : null;
        mPastSigningCertificates = orig.mPastSigningCertificates != null ? orig.mPastSigningCertificates.clone() : null;
        mSchemeVersion = orig.mSchemeVersion;
    }

    private SigningInfo(Parcel source) {
        mSignatures = source.createTypedArray(Signature.CREATOR);
        mPastSigningCertificates = source.createTypedArray(Signature.CREATOR);
        mSchemeVersion = source.readInt();
    }

    /**
     * Although relatively uncommon, packages may be signed by more than one signer, in which case
     * their identity is viewed as being the set of all signers, not just any one.
     */
    public boolean hasMultipleSigners() {
        return mSignatures != null && mSignatures.length > 1;
    }

    /**
     * APK Signature Scheme v3 enables packages to provide a proof-of-rotation record that the
     * platform verifies, and uses, to allow the use of new signing certificates.
     */
    public boolean hasPastSigningCertificates() {
        return mPastSigningCertificates != null && mPastSigningCertificates.length > 0;
    }

    /**
     * Returns the signing certificates this package has proven it is authorized to use. This
     * includes both the signing certificate associated with the signer of the package and the
     * past signing certificates it included as its proof of signing certificate rotation.
     */
    public Signature[] getSigningCertificateHistory() {
        if (hasMultipleSigners()) {
            return null;
        } else if (!hasPastSigningCertificates()) {
            // this package is only signed by one signer with no history, return it
            return mSignatures;
        } else {
            // this package has provided proof of past signing certificates, include them
            return mPastSigningCertificates;
        }
    }

    /** Returns the signing certificates used to sign the APK contents of this application. */
    public Signature[] getApkContentsSigners() {
        return mSignatures;
    }

    /** AOSP (API 35): the version of the signing scheme used to sign the APK. */
    public int getSchemeVersion() {
        return mSchemeVersion;
    }

    /** AOSP (API 35): the public keys of the signers. */
    public Collection<PublicKey> getPublicKeys() {
        LinkedHashSet<PublicKey> keys = new LinkedHashSet<>();
        if (mSignatures != null) {
            for (Signature s : mSignatures) {
                try {
                    keys.add(s.getPublicKey());
                } catch (CertificateException e) {
                    // a signature that is not an X.509 certificate has no public key to report
                }
            }
        }
        return keys;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeTypedArray(mSignatures, parcelableFlags);
        dest.writeTypedArray(mPastSigningCertificates, parcelableFlags);
        dest.writeInt(mSchemeVersion);
    }

    public static final Parcelable.Creator<SigningInfo> CREATOR = new Parcelable.Creator<SigningInfo>() {
        @Override
        public SigningInfo createFromParcel(Parcel source) {
            return new SigningInfo(source);
        }

        @Override
        public SigningInfo[] newArray(int size) {
            return new SigningInfo[size];
        }
    };
}
