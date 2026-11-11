	/** AOSP (API 28): the base protection type of protectionLevel. */
	public int getProtection() {
		return protectionLevel & PROTECTION_MASK_BASE;
	}

	/** AOSP (API 28): the additional protection flags of protectionLevel. */
	public int getProtectionFlags() {
		return protectionLevel & ~PROTECTION_MASK_BASE;
	}
