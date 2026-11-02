# Submit a Package to the NEMESIS Package Store

Packages in the [NEMESIS Package Store](https://xwtk.cloud/services/nemesis#package-store) can be installed from the website listing and from NEMESIS Manager. Anyone with an xwtk.Account can submit one.

## Steps

### 1. Create an xwtk.Account

Sign up at [account.xwtk.cloud](https://account.xwtk.cloud/). Skip this if you already have one.

### 2. Build your package

A package is a zip with `NEMESIS.data` in it. Use descriptor `version=1.1` and fill in the keys the store shows:

```ini
[nemesis]
version=1.1
id=vendor.tool
name=My Tool
type=tool
package-version=1.0
short-desc="One line, at most 64 characters."
os="any"
models="any"
depends=""
```

- `id` is yours once you submit it. Nobody else can use it, in any spelling.
- `os` and `models` decide the compatibility color each user sees, so set them to what you tested. `depends` is checked at install. Put `!id` in it for a package yours cannot be installed with.
- A package with an older descriptor is still accepted. It is shown in yellow with a warning that it may not be compatible, for every user.

The full format and the builder are in [04 - Packages](04-Packages.md).

### 3. Test it on a head unit

Install it from a USB drive, use it, and uninstall it. Check that the unit is back to how it was.

### 4. Open the portal

Sign in at [portal.xwtk.cloud/nemesis](https://portal.xwtk.cloud/nemesis/) and choose **Submit a new package**.

### 5. Upload the zip

Give the package's repository as an `https://` address. The portal checks the zip and shows what it read from your descriptor.

### 6. Check the details and submit

If everything is right, choose **Submit for approval**. Your package ID is reserved for your account from then on.

### 7. Wait for the review

The portal shows the status of your submission, and the reason if it is rejected. Once it is approved, the package is listed in the store with the Community badge.

### 8. Publish updates

Choose **Submit new version** on your package and upload a zip with the same ID and a higher `package-version`. The current version stays listed until the new one is approved.
