package cross.stick.whatsapp

import android.content.Context
import android.content.Intent
import android.widget.Toast

object WhatsAppIntentHelper {

    fun addStickerPackToWhatsApp(
        context: Context,
        packId: String,
        packName: String,
        authority: String
    ) {
        launchForPackage(context, packId, packName, authority, "com.whatsapp")
    }

    fun addStickerPackToWhatsAppBusiness(
        context: Context,
        packId: String,
        packName: String,
        authority: String
    ) {
        launchForPackage(context, packId, packName, authority, "com.whatsapp.w4b")
    }

    private fun launchForPackage(
        context: Context,
        packId: String,
        packName: String,
        authority: String,
        packageName: String
    ) {
        val pm = context.packageManager
        if (pm.getLaunchIntentForPackage(packageName) == null) {
            Toast.makeText(context, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", packId)
            putExtra("sticker_pack_authority", authority)
            putExtra("sticker_pack_name", packName)
            setPackage(packageName)
        }
        context.startActivity(intent)
    }
}
