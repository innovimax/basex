package org.basex.data;

import org.basex.util.hash.*;
import org.basex.util.list.*;

/**
 * This class contains full-text positions for a single database node.
 *
 * @author BaseX Team 2005-15, BSD License
 * @author Christian Gruen
 */
public final class FTPos {
  /** Pre value. */
  final int pre;
  /** Positions. */
  IntList list;

  /**
   * Constructor.
   * @param pre pre value
   * @param list sorted positions
   */
  FTPos(final int pre, final IntList list) {
    this.pre = pre;
    this.list = list;
  }

  /**
   * Merges the specified position arrays.
   * @param pos sorted positions
   */
  void union(final IntList pos) {
    final int ps = list.size(), ls = pos.size();
    final IntSet set = new IntSet(ps + ls);
    for(int p = 0, s = ps; p < s; p++) set.add(list.get(p));
    for(int l = 0, s = ls; l < s; l++) set.add(pos.get(l));
    list = new IntList(set.toArray()).sort();
  }

  /**
   * Checks if the specified position is found.
   * @param pos position to be found
   * @return result of check
   */
  public boolean contains(final int pos) {
    return list.sortedIndexOf(pos) >= 0;
  }

  /**
   * Returns the number of positions.
   * @return number of positions
   */
  public int size() {
    return list.size();
  }

  @Override
  public String toString() {
    return pre + ": " + list;
  }
}
