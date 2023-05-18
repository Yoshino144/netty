package io.netty.buffer;



SizeClasses requires {@code pageShifts} to be defined prior to inclusion,
and it in turn defines:
<p>
  LOG2_SIZE_CLASS_GROUP: Log of size class count for each size doubling.
  LOG2_MAX_LOOKUP_SIZE: Log of max size class in the lookup table.
  sizeClasses: Complete table of [index, log2Group, log2Delta, nDelta, isMultiPageSize,
                isSubPage, log2DeltaLookup] tuples.
    index: Size class index.
    log2Group: Log of group base size (no deltas added).
    log2Delta: Log of delta to previous size class.
    nDelta: Delta multiplier.
    isMultiPageSize: 'yes' if a multiple of the page size, 'no' otherwise.
    isSubPage: 'yes' if a subpage size class, 'no' otherwise.
    log2DeltaLookup: Same as log2Delta if a lookup table size class, 'no'
                     otherwise.
<p>
  nSubpages: Number of subpages size classes.
  nSizes: Number of size classes.
  nPSizes: Number of size classes that are multiples of pageSize.

  smallMaxSizeIdx: Maximum small size class index.

  lookupMaxClass: Maximum size class included in lookup table.
  log2NormalMinClass: Log of minimum normal size class.
<p>
  The first size class and spacing are 1 << LOG2_QUANTUM.
  Each group has 1 << LOG2_SIZE_CLASS_GROUP of size classes.

  size = 1 << log2Group + nDelta * (1 << log2Delta)

  The first size class has an unusual encoding, because the size has to be
  split between group and delta*nDelta.

  If pageShift = 13, sizeClasses looks like this:

  (index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup)
<p>
  ( 0,     4,        4,         0,       no,             yes,        4)
  ( 1,     4,        4,         1,       no,             yes,        4)
  ( 2,     4,        4,         2,       no,             yes,        4)
  ( 3,     4,        4,         3,       no,             yes,        4)
<p>
  ( 4,     6,        4,         1,       no,             yes,        4)
  ( 5,     6,        4,         2,       no,             yes,        4)
  ( 6,     6,        4,         3,       no,             yes,        4)
  ( 7,     6,        4,         4,       no,             yes,        4)
<p>
  ( 8,     7,        5,         1,       no,             yes,        5)
  ( 9,     7,        5,         2,       no,             yes,        5)
  ( 10,    7,        5,         3,       no,             yes,        5)
  ( 11,    7,        5,         4,       no,             yes,        5)
  ...
  ...
  ( 72,    23,       21,        1,       yes,            no,        no)
  ( 73,    23,       21,        2,       yes,            no,        no)
  ( 74,    23,       21,        3,       yes,            no,        no)
  ( 75,    23,       21,        4,       yes,            no,        no)
<p>
  ( 76,    24,       22,        1,       yes,            no,        no)


public class Test {
}
