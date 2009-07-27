package org.basex.index;

import static org.basex.Text.*;
import static org.basex.data.DataText.*;
import static org.basex.util.Token.*;
import java.io.IOException;
import org.basex.BaseX;
import org.basex.core.Prop;
import org.basex.data.Data;
import org.basex.io.DataAccess;
import org.basex.util.IntList;
import org.basex.util.Performance;
import org.basex.util.TokenBuilder;
import org.basex.util.Tokenizer;

/**
 * This class indexes text contents in a compressed trie on disk.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-09, ISC License
 * @author Christian Gruen
 * @author Sebastian Gath
 */
public final class FTTrie extends FTIndex {
  // save each node: l, t1, ..., tl, n1, v1, ..., nu, vu, s, p
  // l = length of the token t1, ..., tl
  // u = number of next nodes n1, ..., nu
  // v1= the first byte of each token n1 points, ...
  // s = size of pre values saved at pointer p
  // [byte, byte[l], byte, int, byte, ..., int, long]

  /** Trie structure on disk. */
  private final DataAccess inN;
  // ftdata is stored here, with pre1, ..., preu, pos1, ..., posu
  /** FTData on disk. */
  public final DataAccess inD;
  // each node entries size is stored here
  /** FTData sizes on disk. */
  private final DataAccess inS;
  /** Id on data, corresponding to the current node entry. */
  private long did;

  /**
   * Constructor, initializing the index structure.
   * @param d data reference
   * @throws IOException IO Exception
   */
  public FTTrie(final Data d) throws IOException {
    super(d);
    final String db = d.meta.name;
    final Prop pr = d.meta.prop;
    inN = new DataAccess(db, DATAFTX + 'a', pr);
    inD = new DataAccess(db, DATAFTX + 'b', pr);
    inS = new DataAccess(db, DATAFTX + 'c', pr);
  }

  @Override
  public byte[] info() {
    final TokenBuilder tb = new TokenBuilder();
    tb.add(TRIE + NL);
    tb.add("- %: %\n", CREATESTEM, BaseX.flag(data.meta.ftst));
    tb.add("- %: %\n", CREATECS, BaseX.flag(data.meta.ftcs));
    tb.add("- %: %\n", CREATEDC, BaseX.flag(data.meta.ftdc));
    final long l = inN.length() + inD.length() + inS.length();
    tb.add(SIZEDISK + Performance.format(l, true) + NL);
    final IndexStats stats = new IndexStats(data.meta.prop);
    addOccs(0, stats, EMPTY);
    stats.print(tb);
    return tb.finish();
  }

  @Override
  public int nrIDs(final IndexToken ind) {
    // skip result count for queries which stretch over multiple index entries
    final Tokenizer fto = (Tokenizer) ind;
    if(fto.fz || fto.wc) return 1;

    final byte[] tok = fto.get();
    final int id = cache.id(tok);
    if(id > 0) return cache.getSize(id);

    int size = 0;
    long poi = 0;
    final int[] ne = nodeId(0, tok);
    if(ne != null && ne[ne.length - 1] > 0) {
      size = ne[ne.length - 1];
      poi = did;
    }
    cache.add(tok, size, poi);
    return size;
  }

  @Override
  public IndexIterator ids(final IndexToken ind) {
    final Tokenizer ft = (Tokenizer) ind;
    final byte[] tok = ft.get();

    // support fuzzy search
    if(ft.fz) {
      int k = data.meta.prop.num(Prop.LSERR);
      if(k == 0) k = tok.length >> 2;
      return fuzzy(0, null, -1, tok, 0, 0, 0, k, ft.fast);
    }

    // support wildcards
    if(ft.wc) {
      final int pw = indexOf(tok, '.');
      if(pw != -1) return wc(tok, pw, ft.fast);
    }

    // return cached or new result
    final int id = cache.id(tok);
    return id == 0 ? get(0, tok, ft.fast) :
      iter(cache.getPointer(id), cache.getSize(id), inD, ft.fast);
  }

  @Override
  public synchronized void close() throws IOException {
    inD.close();
    inS.close();
    inN.close();
  }

  /**
   * Traverses the trie and returns a result iterator.
   * @param id on node array (in main memory)
   * @param searchNode search nodes value
   * @param f fast evaluation
   * @return int[][] array with pre-values and corresponding positions
   * for each pre-value
   */
  private FTIndexIterator get(final int id, final byte[] searchNode,
      final boolean f) {
    if(searchNode == null || searchNode.length == 0)
      return FTIndexIterator.EMP;

    final int[] ne = nodeId(id, searchNode);
    return ne == null ? FTIndexIterator.EMP :
      iter(did, ne[ne.length - 1], inD, f);
  }

  /**
   * Traverses the trie and returns a found node for the searched value.
   * Returns data from node or null.
   * @param id on node array (in main memory)
   * @param sn search nodes value
   * @return int id on node saving the data
   */
  private int[] nodeId(final int id, final byte[] sn) {
    byte[] vsn = sn;

    // read data entry from disk
    final int[] ne = entry(id);

    if(id != 0) {
      int i = 0;
      while(i < vsn.length && i < ne[0] && ne[i + 1] == vsn[i]) i++;
      // node not contained
      if(i != ne[0]) return null;
      // leaf node found with appropriate value
      if(i == vsn.length) return ne;

      // cut valueSearchNode for value current node
      final byte[] tmp = new byte[vsn.length - i];
      System.arraycopy(vsn, i, tmp, 0, tmp.length);
      vsn = tmp;
    }

    // scan succeeding node
    final int pos = insPos(ne, vsn[0]);
    return pos < 0 ? null : nodeId(ne[pos], vsn);
  }

  /**
   * Collects all tokens and their sizes found in the index structure.
   * @param cn id on nodeArray, current node
   * @param st statistics reference
   * @param tok current token
   */
  private void addOccs(final int cn, final IndexStats st, final byte[] tok) {
    final int[] ne = entry(cn);
    byte[] nt = tok;
    if(cn > 0) {
      nt = new byte[tok.length + ne[0]];
      System.arraycopy(tok, 0, nt, 0, tok.length);
      for(int i = 0; i < ne[0]; i++) nt[tok.length + i] = (byte) ne[i + 1];
      final int size = ne[ne.length - 1];
      if(size > 0 && st.adding(size)) st.add(nt);
    }
    if(hasNextNodes(ne)) {
      for(int i = ne[0] + 1; i < ne.length - 1; i += 2) addOccs(ne[i], st, nt);
    }
  }

  /**
   * Read node entry from disk.
   * @param id on node array (in main memory)
   * @return node entry from disk
   */
  private int[] entry(final long id) {
    int sp = inS.read4(id * 4);
    final int ep = inS.read4((id + 1) * 4);
    final IntList il = new IntList();
    inN.cursor(sp++);
    final int l = inN.read1();
    il.add(l);
    for(int j = 0; j < l; j++) il.add(inN.read1());
    sp += l;

    // inner node
    while(sp + 9 < ep) {
      il.add(inN.read4());
      il.add(inN.read1());
      sp += 5;
    }
    il.add(inN.read4(ep - 9));
    did = inN.read5(ep - 5);
    return il.finish();
  }

  /**
   * Checks whether a node is an inner node or a leaf node.
   * @param ne current node entry.
   * @return boolean leaf node or inner node
   */
  private boolean hasNextNodes(final int[] ne) {
    return ne[0] + 1 < ne.length - 1;
  }

  /**
   * Uses linear search for finding inserting position.
   * returns:
   * -1 if no inserting position was found
   * 0 if any successor exists, or 0 is inserting position
   * n here to insert
   * n and found = true, if nth item is occupied and here to insert
   *
   * @param cne current node entry
   * @param ins byte looking for
   * @return inserting position
   */
  private int insPos(final int[] cne, final byte ins) {
    int i = cne[0] + 1;
    final int s = cne.length - 1;
    while(i < s && diff((byte) cne[i + 1], ins) < 0) i += 2;
    return i < s && cne[i + 1] == ins ? i : -1;
  }

  /** saves astericsWildCardTraversing result
  has to be re-init each time (before calling method). */
  private FTIndexIterator idata;

  /** counts number of chars skip per astericsWildCardTraversing. */
  private int countSkippedChars;

  /**
   * Looking up node with value, which match ending.
   * The parameter lastFound shows, whether chars were found in last recursive
   * call, which correspond to the ending, consequently those chars are
   * considered, which occur successive in ending.
   * pointerNode shows the position comparison between value[nodeId] and
   * ending starts
   * pointerEnding shows the position comparison between ending and
   * value[nodeId] starts
   *
   * @param node id on node
   * @param ending ending of value
   * @param lastFound boolean if value was found in last run
   * @param pointerNode pointer on current node
   * @param pointerEnding pointer on value ending
   * @param f fast evaluation
   */
  private void wc(final int node, final byte[] ending, final boolean lastFound,
      final int pointerNode, final int pointerEnding, final boolean f) {

    int j = pointerEnding;
    int i = pointerNode;
    boolean last = lastFound;
    final int[] ne = entry(node);
    final long tdid = did;

    // wildcard at the end
    if(ending == null || ending.length == 0) {
      // save data current node
      if(ne[ne.length - 1] > 0) {
        idata = FTIndexIterator.union(
            iter(tdid, ne[ne.length - 1], inD, f), idata);
      }
      if(hasNextNodes(ne)) {
        // preorder traversal through trie
        for(int t = ne[0] + 1; t < ne.length - 1; t += 2) {
          wc(ne[t], null, last, 0, 0, f);
        }
      }
      return;
    }

    // compare chars current node and ending
      // skip all unlike chars, if any suitable was found
      while(!last && i < ne[0] + 1 && ne[i] != ending[j]) i++;

      // skip all chars, equal to first char
      while(i + ending.length < ne[0] + 1 && ne[i + 1] == ending[0]) i++;

      countSkippedChars = countSkippedChars + i - pointerNode - 1;

      while(i < ne[0] + 1 && j < ending.length && ne[i] == ending[j]) {
        i++;
        j++;
        last = true;
      }

    // not processed all chars from node, but all chars from
    // ending were processed or root
    if(node == 0 || j == ending.length && i < ne[0] + 1) {
      if(!hasNextNodes(ne)) {
        countSkippedChars = 0;
        return;
      }

      //final int[] nextNodes = getNextNodes(ne);
      // preorder search in trie
      for(int t = ne[0] + 1; t < ne.length - 1; t += 2) {
        wc(ne[t], ending, false, 1, 0, f);
      }
      countSkippedChars = 0;
      return;
    } else if(j == ending.length && i == ne[0] + 1) {
      // all chars form node and all chars from ending done
      idata = FTIndexIterator.union(
          iter(tdid, ne[ne.length - 1], inD, f), idata);

      countSkippedChars = 0;

      // node has successors and is leaf node
      if(hasNextNodes(ne)) {
        // preorder search in trie
        for(int t = ne[0] + 1; t < ne.length - 1; t += 2) {
          if(j == 1) {
            wc(ne[t], ending, false, 0, 0, f);
          }
          wc(ne[t], ending, last, 0, j, f);
        }
      }

      return;
    } else if(j < ending.length && i < ne[0] + 1) {
      // still chars from node and still chars from ending left, pointer = 0 and
      // restart searching
      if(!hasNextNodes(ne)) {
        countSkippedChars = 0;
        return;
      }

      // restart searching at node, but value-position i
      wc(node, ending, false, i + 1, 0, f);
      return;
    } else if(j < ending.length && i == ne[0] + 1) {
      // all chars form node processed, but not all chars from processed

      // move pointer and go on
      if(!hasNextNodes(ne)) {
        countSkippedChars = 0;
        return;
      }

      // preorder search in trie
      for(int t = ne[0] + 1; t < ne.length - 1; t += 2) {
        // compare only first char from ending
        if(j == 1) {
          wc(ne[t], ending, last, 1, 0, f);
        }
        wc(ne[t], ending, last, 1, j, f);
      }
    }
  }

  /**
   * Save number compared chars at wildcard search.
   * counter[0] = total number compared chars
   * counter[1] = number current method call (gets initialized before each call)
   */
  private int[] counter;

  /**
   * Saves node values from .-wildcard search according to records in id-array.
   */
  private byte[] valuesFound;

  /**
   * Method for wildcards search in trie.
   * @param sn search nodes value
   * @param pos position
   * @param f fast evaluation
   * @return data int[][]
   */
  private FTIndexIterator wc(final byte[] sn, final int pos, final boolean f) {
    // init counter
    counter = new int[2];
    return wc(0, sn, pos, false, f);
  }

  /**
   * Supports different wildcard operators: ., .+, .* and .?.
   * PosWildCard points on bytes[], at position, where .  is situated
   * recCall flags recursive calls
   *
   * @param cn current node
   * @param sn value looking for
   * @param posw wildcards position
   * @param recCall first call??
   * @param f fast evaluation
   * @return data result ids
   */
  private FTIndexIterator wc(final int cn, final byte[] sn,
      final int posw, final boolean recCall, final boolean f) {

    final byte[] vsn = sn;
    byte[] aw = null;
    byte[] bw = null;

    final int currentLength = 0;
    int resultNode;

    FTIndexIterator d = FTIndexIterator.EMP;
    // wildcard not at beginning
    if(posw > 0) {
      // copy part before wildcard
      bw = new byte[posw];
      System.arraycopy(vsn, 0, bw, 0, posw);
      resultNode = wc(cn, bw);
      if(resultNode == -1) return FTIndexIterator.EMP;
    } else {
      resultNode = 0;
    }

    final byte wildcard = posw + 1 >= vsn.length ? (byte) '.' : vsn[posw + 1];
    if(wildcard == '?') {
      // append 0 or 1 symbols
      // look in trie without wildcard
      byte[] sc = new byte[vsn.length - 2 - currentLength];
      // copy unprocessed part before wildcard
      if(bw != null) {
        System.arraycopy(bw, 0, sc, 0, bw.length);
      }
      // copy part after wildcard
      if(bw == null) {
        System.arraycopy(vsn, posw + 2, sc, 0, sc.length);
      } else {
        System.arraycopy(vsn, posw + 2, sc, bw.length, sc.length - bw.length);
      }

      d = get(0, sc, f);

      // lookup in trie with . as wildcard
      sc = new byte[vsn.length - 1];
      if(bw != null) {
        // copy unprocessed part before wildcard
        System.arraycopy(bw, 0, sc, 0, bw.length);
        sc[bw.length] = '.';

        // copy part after wildcard
        System.arraycopy(vsn, posw + 2, sc, bw.length + 1,
            sc.length - bw.length - 1);
      } else {
        // copy unprocessed part before wildcard
        sc[0] = '.';
        // copy part after wildcard
        System.arraycopy(vsn, posw + 2, sc, 1, sc.length - 1);
      }
      // attach both result
      d = FTIndexIterator.union(wc(0, sc, posw, false, f), d);
      return d;
    }

    if(wildcard == '*') {
      // append 0 or n symbols
      // valueSearchNode == .*
      if(!(posw == 0 && vsn.length == 2)) {
        // lookup in trie without wildcard
        final byte[] searchChar = new byte[vsn.length - 2 - currentLength];
        // copy unprocessed part before wildcard
        if(bw != null) {
          System.arraycopy(bw, 0, searchChar, 0, bw.length);
        }
        // copy part after wildcard
        if(bw == null) {
          aw = new byte[searchChar.length];
          System.arraycopy(vsn, posw + 2, searchChar, 0, searchChar.length);
          System.arraycopy(vsn, posw + 2, aw, 0, searchChar.length);
        } else {
          aw = new byte[searchChar.length - bw.length];
          System.arraycopy(vsn, posw + 2, searchChar,
              bw.length, searchChar.length - bw.length);
          System.arraycopy(vsn, posw + 2, aw,
              0, searchChar.length - bw.length);
        }
        d = get(0, searchChar, f);
        // all chars from valueSearchNode are contained in trie
        if(bw != null && counter[1] != bw.length) return d;
      }

      // delete data
      idata = FTIndexIterator.EMP;
      wc(resultNode, aw, false, counter[0], 0, f);
      return FTIndexIterator.union(d, idata);
    }

    if(wildcard == '+') {
      // append 1 or more symbols
      final int[] rne = entry(resultNode);
      final byte[] nvsn = new byte[vsn.length + 1];
      int l = 0;
      if(bw != null) {
        System.arraycopy(bw, 0, nvsn, 0, bw.length);
        l = bw.length;
      }

      if(0 < vsn.length - posw - 2) {
        System.arraycopy(vsn, posw + 2, nvsn, posw + 3, vsn.length - posw - 2);
      }

      nvsn[l + 1] = '.';
      nvsn[l + 2] = '*';
      FTIndexIterator tmpres = FTIndexIterator.EMP;
      // append 1 symbol
      // not completely processed (value current node)
      if(rne[0] > counter[0] && resultNode > 0) {
        // replace wildcard with value from currentCompressedTrieNode
        nvsn[l] = (byte) rne[counter[0] + 1];
        tmpres = wc(nvsn, l + 1, f);
      } else if(rne[0] == counter[0] || resultNode == 0) {
        // all chars from nodes[resultNode] are computed
        // any next values existing
        if(!hasNextNodes(rne)) return FTIndexIterator.EMP;

        for(int t = rne[0] + 1; t < rne.length - 1; t += 2) {
          nvsn[l] = (byte) rne[t + 1];
          tmpres = FTIndexIterator.union(wc(nvsn, l + 1, f), tmpres);
        }
      }
      return tmpres;
    }

    final int[] rne = entry(resultNode);
    // append 1 symbol
    // not completely processed (value current node)
    if(rne[0] > counter[0] && resultNode > 0) {
      // replace wildcard with value from currentCompressedTrieNode
      vsn[posw] = (byte) rne[counter[0] + 1];

      // . wildcards left
      final FTIndexIterator resultData = get(0, vsn, f);
      // save nodeValues for recursive method call
      if(resultData.size != 0 && recCall) {
        valuesFound = new byte[] {(byte) rne[counter[0] + 1]};
      }
      return resultData;
    }

    if(rne[0] == counter[0] || resultNode == 0) {
      // all chars from nodes[resultNode] are computed
      // any next values existing
      if(!hasNextNodes(rne)) return FTIndexIterator.EMP;

      FTIndexIterator tmpNode = FTIndexIterator.EMP;
      aw = new byte[vsn.length - posw];
      System.arraycopy(vsn, posw + 1, aw, 1, aw.length - 1);

      // simple method call
      if(!recCall) {
        for(int t = rne[0] + 1; t < rne.length - 1; t += 2) {
          aw[0] = (byte) rne[t + 1];
          tmpNode = FTIndexIterator.union(get(rne[t], aw, f), tmpNode);
        }
        return tmpNode;
      }

      // method call for .+ wildcard
      valuesFound = new byte[rne.length - 1 - rne[0] - 1];
      for(int t = rne[0] + 1; t < rne.length - 1; t += 2) {
        // replace first letter
        aw[0] = (byte) rne[t + 1];
        valuesFound[t - rne[0] - 1] = (byte) rne[t + 1];
        tmpNode = FTIndexIterator.union(get(rne[t], aw, f), tmpNode);
      }
    }
    return FTIndexIterator.EMP;
  }

  /**
   * Traverses the trie and returns a found node for searchValue;
   * returns the last touched node.
   * @param cn int
   * @param sn int
   * @return id int last touched node
   */
  private int wc(final int cn, final byte[] sn) {
    byte[]vsn = sn;
    final int[] cne = entry(cn);
    if(cn != 0) {
      counter[1] += cne[0];

      int i = 0;
      while(i < vsn.length && i < cne[0] && cne[i + 1] == vsn[i]) i++;

      if(cne[0] == i) {
        if(vsn.length == i) {
          // leaf node found with appropriate value
          counter[0] = i;
          return cn;
        }

        // cut valueSearchNode for value current node
        final byte[] tmp = new byte[vsn.length - i];
        System.arraycopy(vsn, i, tmp, 0, tmp.length);
        vsn = tmp;

        // scan successors currentNode
        final int pos = insPos(cne, vsn[0]);
        if(pos >= 0) return wc(cne[pos], vsn);
      }
      // node not contained
      counter[0] = i;
      counter[1] = counter[1] - cne[0] + i;
      return cn;
    }

    // scan successors current node
    final int pos = insPos(cne, vsn[0]);
    if(pos >= 0) return wc(cne[pos], vsn);

    // node not contained
    counter[0] = -1;
    counter[1] = -1;
    return -1;
  }

  /**
   * Traverses the trie and returns the found node for searchValue;
   * returns data from node or null.
   * @param cn int current node id
   * @param crne byte[] current node entry (of cn)
   * @param crdid long current pointer on data
   * @param sn byte[] search nodes value
   * @param d int counter for deletions
   * @param p int counter for pastes
   * @param r int counter for replacements
   * @param c int counter sum of errors
   * @param f fast evaluation
   * @return int[][]
   */
  private FTIndexIterator fuzzy(final int cn, final int[] crne,
      final long crdid, final byte[] sn, final int d, final int p,
      final int r, final int c, final boolean f) {
    byte[] vsn = sn;
    int[] cne = crne;
    long cdid = crdid;
    if(cne == null) {
      cne = entry(cn);
      cdid = did;
    }

    if(cn != 0) {
      // not root node
      int i = 0;
      while(i < vsn.length && i < cne[0] && cne[i + 1] == vsn[i]) i++;

      if(cne[0] == i) {
        // node entry processed complete
        if(vsn.length == i) {
          // leaf node found with appropriate value
          if(c < d + p + r) return FTIndexIterator.EMP;

          FTIndexIterator ld = FTIndexIterator.EMP;
          ld = iter(cdid, cne[cne.length - 1], inD, f);
          if(hasNextNodes(cne)) {
            for(int t = cne[0] + 1; t < cne.length - 1; t += 2) {
              ld = FTIndexIterator.union(fuzzy(cne[t], null, -1,
                  new byte[]{(byte) cne[t + 1]}, d, p + 1, r, c, f), ld);
            }
          }
          return ld;
        }

        FTIndexIterator ld = FTIndexIterator.EMP;
        byte[] b;
        if(c > d + p + r) {
          // delete char
          b = new byte[vsn.length - 1];
          System.arraycopy(vsn, 0, b, 0, i);
          ld = FTIndexIterator.union(
              fuzzy(cn, cne, cdid, b, d + 1, p, r, c, f), ld);
        }

        // cut valueSearchNode for value current node
        final byte[] tmp = new byte[vsn.length - i];
        System.arraycopy(vsn, i, tmp, 0, tmp.length);
        vsn = tmp;

        // scan successors currentNode
        int[] ne = null;
        long tdid = -1;
        if(hasNextNodes(cne)) {
          for(int k = cne[0] + 1; k < cne.length - 1; k += 2) {
            if(cne[k + 1] == vsn[0]) {
              ne = entry(cne[k]);
              tdid = did;
              b = new byte[vsn.length];
              System.arraycopy(vsn, 0, b, 0, vsn.length);
              ld = FTIndexIterator.union(
                  fuzzy(cne[k], ne, tdid, b, d, p, r, c, f), ld);
            }

            if(c > d + p + r) {
              if(ne == null) {
                ne = entry(cne[k]);
                tdid = did;
              }
              // paste char
              b = new byte[vsn.length + 1];
              b[0] = (byte) cne[k + 1];
              System.arraycopy(vsn, 0, b, 1, vsn.length);
              ld = FTIndexIterator.union(fuzzy(cne[k], ne, tdid,
                  b, d, p + 1, r, c, f), ld);

              if(vsn.length > 0) {
                // delete char
                b = new byte[vsn.length - 1];
                System.arraycopy(vsn, 1, b, 0, b.length);
                ld = FTIndexIterator.union(fuzzy(cne[k], ne, tdid,
                    b, d + 1, p, r, c, f), ld);
                // replace char
                b = new byte[vsn.length];
                System.arraycopy(vsn, 1, b, 1, vsn.length - 1);
                b[0] = (byte) ne[1];
                ld = FTIndexIterator.union(fuzzy(cne[k], ne, tdid,
                    b, d, p, r + 1, c, f), ld);
              }
            }
          }
        }
        return ld;
      }

      FTIndexIterator ld = FTIndexIterator.EMP;

      if(c > d + p + r) {
        // paste char
        byte[] b = new byte[vsn.length + 1];
        System.arraycopy(vsn, 0, b, 0, i);
        b[i] = (byte) cne[i + 1];
        System.arraycopy(vsn, i, b, i + 1, vsn.length - i);

        ld = fuzzy(cn, cne, cdid, b, d, p + 1, r, c, f);

        if(vsn.length > 0 && i < vsn.length) {
          // replace
          b = new byte[vsn.length];
          System.arraycopy(vsn, 0, b, 0, vsn.length);

          b[i] = (byte) cne[i + 1];
          ld = FTIndexIterator.union(fuzzy(cn, cne, cdid,
              b, d, p, r + 1, c, f), ld);
          if(vsn.length > 1) {
            // delete char
            b = new byte[vsn.length - 1];
            System.arraycopy(vsn, 0, b, 0, i);
            System.arraycopy(vsn, i + 1, b, i, vsn.length - i - 1);
            ld = FTIndexIterator.union(fuzzy(cn, cne, cdid,
                b, d + 1, p, r, c, f), ld);
          }
        }
      }
      return ld;
    }

    int[] ne = null;
    long tdid = -1;
    FTIndexIterator ld = FTIndexIterator.EMP;

    byte[] b;
    if(hasNextNodes(cne)) {
      for(int k = cne[0] + 1; k < cne.length - 1; k += 2) {
        if(cne[k + 1] == vsn[0]) {
          ne = entry(cne[k]);
          tdid = did;
          b = new byte[vsn.length];
          System.arraycopy(vsn, 0, b, 0, vsn.length);
          ld = FTIndexIterator.union(
              fuzzy(cne[k], ne, tdid, b, d, p, r, c, f), ld);
        }
        if(c > d + p + r) {
          if(ne == null) {
            ne = entry(cne[k]);
            tdid = did;
          }
          // paste char
          b = new byte[vsn.length + 1];
          b[0] = (byte) ne[1];
          System.arraycopy(vsn, 0, b, 1, vsn.length);
          ld = FTIndexIterator.union(fuzzy(cne[k], ne, tdid,
              b, d, p + 1, r, c, f), ld);

          if(vsn.length > 0) {
            // delete char
            b = new byte[vsn.length - 1];
            System.arraycopy(vsn, 1, b, 0, b.length);
            ld = FTIndexIterator.union(fuzzy(cne[k], ne, tdid,
                b, d + 1, p, r, c, f), ld);
              // replace
            b = new byte[vsn.length];
            System.arraycopy(vsn, 1, b, 1, vsn.length - 1);
              b[0] = (byte) ne[1];
              ld = FTIndexIterator.union(fuzzy(cne[k], ne, tdid,
                  b, d, p, r + 1, c, f), ld);
          }
        }
      }
    }
    return ld;
  }
}
