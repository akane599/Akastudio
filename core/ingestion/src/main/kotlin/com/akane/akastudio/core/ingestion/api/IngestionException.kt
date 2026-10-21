package com.akane.akastudio.core.ingestion.api

import com.akane.akastudio.core.ingestion.model.IngestionError
import java.io.IOException

class IngestionException(val error: IngestionError) : IOException(error.message)
