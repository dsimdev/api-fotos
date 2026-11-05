export type Health = {
  status: string;
  version: string;
  commit: string;
  startedAt: string;
  upFor: string;
};

/** FOUND, NOT_FOUND (the store does not sell it) or ERROR (it could not answer). */
export type StoreStatus = 'FOUND' | 'NOT_FOUND' | 'ERROR';

export type StoreResult = {
  store: string;
  status: StoreStatus;
  /** For ERROR: UNAVAILABLE, RATE_LIMITED, BLOCKED or UNEXPECTED_RESPONSE. */
  error: string | null;
  productName: string | null;
  brand: string | null;
  pageUrl: string | null;
  /** In the store's order. Position 0 is the main picture; later ones may be the back of the pack. */
  imageUrls: string[];
  millis: number;
};

export type LookupResponse = {
  ean: string;
  stores: StoreResult[];
};
