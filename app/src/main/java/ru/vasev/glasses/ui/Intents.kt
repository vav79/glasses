package ru.vasev.glasses.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import ru.vasev.glasses.R
import ru.vasev.glasses.notes.Note
import java.util.Locale

/** «Проложить маршрут»: точка заметки в приложении карт (`geo:` понимают все карты, в том числе Google и Яндекс). */
fun openRoute(context: Context, note: Note) {
    val lat = note.latitude ?: return
    val lon = note.longitude ?: return
    val point = String.format(Locale.US, "%.6f,%.6f", lat, lon)
    val label = Uri.encode(note.text.take(60))
    start(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=$point($label)")), R.string.no_maps_app)
}

private fun start(context: Context, intent: Intent, noAppMessage: Int) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, noAppMessage, Toast.LENGTH_SHORT).show()
    }
}

/** Маршрут или точка в приложении карт; нужного приложения нет — любое, что понимает ссылку. */
fun openMapsLink(context: Context, link: ru.vasev.glasses.nav.MapsLink) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(link.packageName?.let { intent.setPackage(it) } ?: intent)
    } catch (e: ActivityNotFoundException) {
        if (link.packageName != null) start(context, intent.setPackage(null), R.string.no_maps_app)
        else Toast.makeText(context, R.string.no_maps_app, Toast.LENGTH_SHORT).show()
    }
}
