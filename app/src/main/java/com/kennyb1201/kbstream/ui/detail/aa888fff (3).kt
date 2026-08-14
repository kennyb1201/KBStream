package com.kennyb1201.kbstream.ui.detail

// Patch helper generated from the user-provided DetailScreen.
// Intended fix: add focusRestorer() to the parent details LazyColumn.

/*
Change this:

LazyColumn(
    modifier = Modifier.weight(1f, fill = true).fillMaxWidth().focusGroup(),
    contentPadding = PaddingValues(top = 8.dp)
) {

To this:

LazyColumn(
    modifier = Modifier
        .weight(1f, fill = true)
        .fillMaxWidth()
        .focusGroup()
        .focusRestorer(),
    contentPadding = PaddingValues(top = 8.dp)
) {
*/
EOF && ls -l /tmp/output/DetailScreen_fixed.kt
