/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.os;

/**
 * Reference to a Handler, which others can use to send messages to it (AOSP). Its binder is the
 * Handler's IMessenger stub, so a Messenger rebuilt from that binder (a service's onBind, a
 * parcel) reaches the same Handler.
 */
public final class Messenger implements Parcelable {
    private final IMessenger mTarget;

    /** Create a new Messenger pointing to the given Handler. */
    public Messenger(Handler target) {
        mTarget = target.getIMessenger();
    }

    /** Send a Message to this Messenger's Handler. */
    public void send(Message message) throws RemoteException {
        mTarget.send(message);
    }

    /** Retrieve the IBinder that this Messenger is using to communicate with its associated Handler. */
    public IBinder getBinder() {
        return mTarget.asBinder();
    }

    /** Comparison operator on two Messenger objects, such that true is returned then they both point to the same Handler. */
    public boolean equals(Object otherObj) {
        if (otherObj == null) {
            return false;
        }
        try {
            return mTarget.asBinder().equals(((Messenger) otherObj).mTarget.asBinder());
        } catch (ClassCastException e) {
        }
        return false;
    }

    public int hashCode() {
        return mTarget.asBinder().hashCode();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeStrongBinder(mTarget.asBinder());
    }

    public static final Parcelable.Creator<Messenger> CREATOR = new Parcelable.Creator<Messenger>() {
        public Messenger createFromParcel(Parcel in) {
            IBinder target = in.readStrongBinder();
            return target != null ? new Messenger(target) : null;
        }

        public Messenger[] newArray(int size) {
            return new Messenger[size];
        }
    };

    /** Convenience function for writing either a Messenger or null pointer to a Parcel. */
    public static void writeMessengerOrNullToParcel(Messenger messenger, Parcel out) {
        out.writeStrongBinder(messenger != null ? messenger.mTarget.asBinder() : null);
    }

    /** Convenience function for reading either a Messenger or null pointer from a Parcel. */
    public static Messenger readMessengerOrNullFromParcel(Parcel in) {
        IBinder b = in.readStrongBinder();
        return b != null ? new Messenger(b) : null;
    }

    /** Create a Messenger from a raw IBinder, which had previously been retrieved with {@link #getBinder}. */
    public Messenger(IBinder target) {
        mTarget = IMessenger.Stub.asInterface(target);
    }
}
