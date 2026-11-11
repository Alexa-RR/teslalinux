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
 * AOSP core/java/android/os/IMessenger.aidl ("oneway interface IMessenger { void send(in
 * Message msg); }"), written out as the AIDL compiler generates it.
 * @hide
 */
public interface IMessenger extends IInterface {
    void send(Message msg) throws RemoteException;

    /** Local-side IPC implementation stub class. */
    abstract class Stub extends Binder implements IMessenger {
        private static final String DESCRIPTOR = "android.os.IMessenger";
        static final int TRANSACTION_send = IBinder.FIRST_CALL_TRANSACTION + 0;

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        /** The local object for a binder of this process, else a proxy. */
        public static IMessenger asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IMessenger) {
                return (IMessenger) iin;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION:
                    reply.writeString(DESCRIPTOR);
                    return true;
                case TRANSACTION_send: {
                    data.enforceInterface(DESCRIPTOR);
                    Message msg = data.readInt() != 0 ? Message.CREATOR.createFromParcel(data) : null;
                    this.send(msg);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IMessenger {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public void send(Message msg) throws RemoteException {
                Parcel data = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    if (msg != null) {
                        data.writeInt(1);
                        msg.writeToParcel(data, 0);
                    } else {
                        data.writeInt(0);
                    }
                    mRemote.transact(TRANSACTION_send, data, null, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }
        }
    }
}
