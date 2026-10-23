package com.mercureal.voice

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast

/** Long-press config for the routine widget: pick one saved routine. */
class WidgetConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val names = Routines.all(this).map { it.name }
        if (names.isEmpty()) {
            Toast.makeText(this, "no routines saved yet. make one in the app first", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        setContentView(list)
        list.setOnItemClickListener { _, _, pos, _ ->
            RoutineWidgetStore.set(this, widgetId, names[pos])
            RoutineWidgetProvider().onUpdate(this, AppWidgetManager.getInstance(this), intArrayOf(widgetId))
            setResult(RESULT_OK, android.content.Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}
