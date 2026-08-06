@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun TagScreen(
    id: Int,
    name: String,
    isKeyword: Boolean,
    mediaType: String,
    onBack: () -> Unit = {},
    onNavigateDetail: (String, String) -> Unit,
    viewModel: TagViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val sections by viewModel.sections.collectAsState()
    val watchedKeys by viewModel.watchedKeys.collectAsState()
    val resolvedIds by viewModel.resolvedIds.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val scope = rememberCoroutineScope()
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(id, isKeyword, mediaType) {
        viewModel.load(id, isKeyword, mediaType)
    }

    LaunchedEffect(sections, isLoading) {
        val hasItems = sections.any { it.items.isNotEmpty() }
        if (!isLoading && hasItems) {
            delay(120)
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides LocalTvBringIntoViewSpec) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            when {
                isLoading -> {
                    Text("Loading...")
                }

                error != null && sections.isEmpty() -> {
                    Text("Error: $error")
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusGroup(),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        item(key = "header") {
                            Column(modifier = Modifier.padding(bottom = 20.dp)) {
                                Text(
                                    text = if (isKeyword) "Keyword" else "Genre",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.headlineLarge,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Text(
                                    text = mediaType.uppercase(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 6.dp)
                                )

                                error?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }

                        sections.forEachIndexed { sectionIndex, section ->
                            item(key = "title_${section.title}_$sectionIndex") {
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                            }

                            item(key = "row_${section.title}_$sectionIndex") {
                                LazyRow(
                                    contentPadding = PaddingValues(bottom = 18.dp),
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    items(
                                        items = section.items,
                                        key = { "${it.mediaType}:${it.item.id}" }
                                    ) { studioItem ->
                                        val tmdbId = studioItem.item.id
                                        val itemMediaType = studioItem.mediaType.lowercase()
                                        val imdbId = resolvedIds[
                                            viewModel.lookupKey(tmdbId, itemMediaType)
                                        ]

                                        val isWatched = imdbId?.let {
                                            viewModel.watchedKey(it, itemMediaType) in watchedKeys
                                        } == true

                                        TagPosterCard(
                                            item = studioItem,
                                            isWatched = isWatched,
                                            onClick = {
                                                scope.launch {
                                                    val resolved = imdbId ?: viewModel.resolveImdbId(tmdbId, itemMediaType)
                                                    if (!resolved.isNullOrBlank()) {
                                                        onNavigateDetail(itemMediaType, resolved)
                                                    }
                                                }
                                            },
                                            modifier = if (
                                                sectionIndex == 0 &&
                                                section.items.firstOrNull()?.item?.id == tmdbId
                                            ) {
                                                Modifier.focusRequester(firstItemFocusRequester)
                                            } else {
                                                Modifier
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "back_button") {
                            KBCard(
                                onClick = onBack,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text(
                                    "BACK",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }

                        item(key = "bottom_spacer") {
                            Box(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}
