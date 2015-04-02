package org.basex.query.util.fingertree;

import java.util.*;

/**
 * A builder for {@link FingerTree}s from leaf nodes.
 *
 * @author BaseX Team 2005-15, BSD License
 * @author Leo Woerteler
 *
 * @param <E> element type
 */
@SuppressWarnings("unchecked")
public final class FingerTreeBuilder<E> {
  /** The root node, {@code null} if the tree is empty. */
  private Object root;

  /**
   * Checks if this builder is empty, i.e. if no leaf nodes were added to it.
   * @return {@code true} if the builder is empty, {@code false} otherwise
   */
  public boolean isEmpty() {
    return root == null;
  }

  /**
   * Adds a leaf node to the front of the tree.
   * @param leaf the leaf node to add
   */
  public void prepend(final Node<E, E> leaf) {
    if(root == null) {
      root = new BufferNode<>(leaf);
    } else if(root instanceof BufferNode) {
      ((BufferNode<E, E>) root).prepend(leaf);
    } else {
      final BufferNode<E, E> newRoot = new BufferNode<>((FingerTree<E, E>) root);
      newRoot.prepend(leaf);
      root = newRoot;
    }
  }

  /**
   * Adds a leaf node to the back of the tree.
   * @param leaf the leaf node to add
   */
  public void append(final Node<E, E> leaf) {
    if(root == null) {
      root = new BufferNode<>(leaf);
    } else if(root instanceof BufferNode) {
      ((BufferNode<E, E>) root).append(leaf);
    } else {
      final BufferNode<E, E> newRoot = new BufferNode<>((FingerTree<E, E>) root);
      newRoot.append(leaf);
      root = newRoot;
    }
  }

  /**
   * Appends another finger tree to this builder.
   * @param tree finger tree to append
   */
  public void append(final FingerTree<E, E> tree) {
    if(!tree.isEmpty()) {
      if(root == null) {
        root = new BufferNode<>(tree);
      } else if(root instanceof BufferNode) {
        ((BufferNode<E, E>) root).append(tree);
      } else {
        final BufferNode<E, E> newRoot = new BufferNode<>((FingerTree<E, E>) root);
        newRoot.append(tree);
        root = newRoot;
      }
    }
  }

  /**
   * Builds a finger tree from the current state of this builder.
   * @return the resulting finger tree
   */
  public FingerTree<E, E> freeze() {
    return root == null ? FingerTree.<E>empty() :
      root instanceof BufferNode ? ((BufferNode<E, E>) root).freeze() : (FingerTree<E, E>) root;
  }

  /**
   * Writes the elements contained in this builder onto the given string builder.
   * @param sb string builder
   */
  public void toString(final StringBuilder sb) {
    if(root != null) {
      if(root instanceof BufferNode) {
        ((BufferNode<E, E>) root).toString(sb);
      } else {
        boolean first = true;
        for(final E e : (FingerTree<E, E>) root) {
          if(!first) sb.append(", ");
          else first = false;
          sb.append(e);
        }
      }
    }
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(getClass().getSimpleName()).append('[');
    toString(sb);
    return sb.append(']').toString();
  }

  /**
   * Node of the middle tree.
   *
   * @param <N> node type
   * @param <E> element type
   */
  private static class BufferNode<N, E> {
    /** Size of inner nodes to create. */
    private static final int NODE_SIZE = FingerTree.MAX_ARITY;
    /** Maximum number of elements in a digit. */
    private static final int MAX_DIGIT = NODE_SIZE + 1;
    /** Maximum number of nodes in the digits. */
    private static final int CAP = 2 * MAX_DIGIT;
    /** Ring buffer for nodes in the digits. */
    final Node<N, E>[] nodes = new Node[CAP];
    /** Number of elements in left digit. */
    int inLeft;
    /** Position of middle between left and right digit in buffer. */
    int midPos = MAX_DIGIT;
    /** Number of elements in right digit. */
    int inRight;
    /**
     * Root node of middle tree, either a {@code FingerTree<Node<N, E>, E>} or a
     * {@code BufferNode<Node<N, E>, E>}.
     */
    Object middle;

    /**
     * Constructs a buffered tree containing the given single node.
     * @param node the initial node
     */
    BufferNode(final Node<N, E> node) {
      prepend(node);
    }

    /**
     * Constructs a buffered tree containing the same contents as the given tree.
     * @param tree the tree to take the contents of
     */
    BufferNode(final FingerTree<N, E> tree) {
      if(tree instanceof SingletonTree) {
        prepend(((SingletonTree<N, E>) tree).elem);
      } else {
        final DeepTree<N, E> deep = (DeepTree<N, E>) tree;
        for(int i = deep.left.length; --i >= 0;) prepend(deep.left[i]);
        final FingerTree<Node<N, E>, E> mid = deep.middle;
        if(!mid.isEmpty()) middle = mid;
        for(final Node<N, E> node : deep.right) append(node);
      }
    }

    /**
     * Adds a node to the front of this tree.
     * @param node the node to add
     */
    void prepend(final Node<N, E> node) {
      if(inLeft < MAX_DIGIT) {
        nodes[(midPos - inLeft - 1 + CAP) % CAP] = node;
        inLeft++;
      } else if(middle == null && inRight < MAX_DIGIT) {
        midPos = (midPos - 1 + CAP) % CAP;
        nodes[(midPos - inLeft + CAP) % CAP] = node;
        inRight++;
      } else {
        final int l = (midPos - inLeft + CAP) % CAP;
        final Node<Node<N, E>, E> next = new InnerNode<>(copy(l + 1, inLeft - 1));
        nodes[(midPos - 1 + CAP) % CAP] = nodes[l];
        nodes[(midPos - 2 + CAP) % CAP] = node;
        inLeft = 2;
        if(middle == null) middle = new BufferNode<>(next);
        else midBuffer().prepend(next);
      }
    }

    /**
     * Adds a node to the back of this tree.
     * @param node the node to add
     */
    void append(final Node<N, E> node) {
      if(inRight < MAX_DIGIT) {
        nodes[(midPos + inRight) % CAP] = node;
        inRight++;
      } else if(middle == null && inLeft < MAX_DIGIT) {
        midPos = (midPos + 1) % CAP;
        nodes[(midPos + inRight - 1) % CAP] = node;
        inLeft++;
      } else {
        final Node<Node<N, E>, E> next = new InnerNode<>(copy(midPos, inRight - 1));
        nodes[midPos] = nodes[(midPos + inRight - 1) % CAP];
        nodes[(midPos + 1) % CAP] = node;
        inRight = 2;
        if(middle == null) middle = new BufferNode<>(next);
        else midBuffer().append(next);
      }
    }

    /**
     * Appends the contents of the given tree to this buffer.
     * @param tree finger tree to append
     */
    void append(final FingerTree<N, E> tree) {
      if(!(tree instanceof DeepTree)) {
        if(tree instanceof SingletonTree) append(((SingletonTree<N, E>) tree).elem);
        return;
      }

      final DeepTree<N, E> deep = (DeepTree<N, E>) tree;
      final Node<N, E>[] ls = deep.left, rs = deep.right;
      final int ll = ls.length, rl = rs.length;
      final FingerTree<Node<N, E>, E> mid = deep.middle;

      if(mid.isEmpty()) {
        // add digits
        for(int i = 0; i < ll; i++) append(ls[i]);
        for(int i = 0; i < rl; i++) append(rs[i]);
      } else if(middle == null) {
        // cache previous contents and re-add them afterwards
        final int n = inLeft + inRight;
        final Node<N, E>[] buff = new Node[n + ll];
        copyInto(midPos - inLeft, buff, 0, n);
        System.arraycopy(ls, 0, buff, n, ll);
        inLeft = inRight = 0;
        middle = mid;
        for(int i = buff.length; --i >= 0;) prepend(buff[i]);
        for(int i = 0; i < rl; i++) append(rs[i]);
      } else {
        // inner digits have to be merged
        final int n = inRight + ll;
        final Node<N, E>[] buff = new Node[n];
        copyInto(midPos, buff, 0, inRight);
        System.arraycopy(ls, 0, buff, inRight, ll);
        inRight = 0;
        for(int k = (n + NODE_SIZE - 1) / NODE_SIZE, p = 0; k > 0; k--) {
          final int inNode = (n - p + k - 1) / k;
          final Node<N, E>[] out = new Node[inNode];
          System.arraycopy(buff, p, out, 0, inNode);
          final Node<Node<N, E>, E> sub = new InnerNode<>(out);
          if(middle == null) middle = new BufferNode<>(sub);
          else midBuffer().append(sub);
          p += inNode;
        }
        if(middle == null) middle = mid;
        else midBuffer().append(mid);
        for(int i = 0; i < rl; i++) append(rs[i]);
      }
    }

    /**
     * Creates an {@link FingerTree} containing the elements of this builder.
     * @return the finger tree
     */
    FingerTree<N, E> freeze() {
      final int n = inLeft + inRight;
      if(n == 1) return new SingletonTree<>(nodes[(midPos + inRight - 1 + CAP) % CAP]);
      final int a = middle == null ? n / 2 : inLeft, l = midPos - inLeft;
      final Node<N, E>[] left = copy(l, a), right = copy(l + a, n - a);
      if(middle == null) return DeepTree.get(left, right);

      if(middle instanceof FingerTree) {
        final FingerTree<Node<N, E>, E> tree = (FingerTree<Node<N, E>, E>) middle;
        return DeepTree.get(left, tree, right);
      }

      final BufferNode<Node<N, E>, E> buffer = (BufferNode<Node<N, E>, E>) middle;
      return DeepTree.get(left, buffer.freeze(), right);
    }

    /**
     * Writes the elements contained in this node onto the given string builder.
     * @param sb string builder
     */
    void toString(final StringBuilder sb) {
      boolean first = true;
      for(int i = 0; i < inLeft; i++) {
        final Node<N, E> node = nodes[(midPos - inLeft + i + CAP) % CAP];
        for(final E elem : node) {
          if(first) first = false;
          else sb.append(", ");
          sb.append(elem);
        }
      }
      if(!(middle == null)) {
        if(middle instanceof BufferNode) {
          ((BufferNode<?, ?>) middle).toString(sb.append(", "));
        } else {
          final FingerTree<?, ?> tree = (FingerTree<?, ?>) middle;
          final Iterator<?> iter = tree.iterator();
          while(iter.hasNext()) sb.append(", ").append(iter.next());
        }
      }
      for(int i = 0; i < inRight; i++) {
        final Node<N, E> node = nodes[(midPos + i) % CAP];
        for(final E elem : node) {
          if(first) first = false;
          else sb.append(", ");
          sb.append(elem);
        }
      }
    }

    /**
     * Returns the middle tree as a buffer node.
     * @return middle buffer node
     */
    private BufferNode<Node<N, E>, E> midBuffer() {
      if(middle == null) return null;
      if(middle instanceof BufferNode) return (BufferNode<Node<N, E>, E>) middle;
      final BufferNode<Node<N, E>, E> mid = new BufferNode<>((FingerTree<Node<N, E>, E>) middle);
      middle = mid;
      return mid;
    }

    /**
     * Copies the elements in the given range from the ring buffer into an array.
     * @param start start of the range
     * @param len length of the range
     * @return array containing all nodes in the range
     */
    private Node<N, E>[] copy(final int start, final int len) {
      final Node<N, E>[] out = new Node[len];
      copyInto(start, out, 0, len);
      return out;
    }

    /**
     * Copies the nodes in the given range of the ring buffer into the given array.
     * @param start start position of the range in the ring buffer
     * @param arr output array
     * @param pos start position in the output array
     * @param len length of the range
     */
    private void copyInto(final int start, final Node<N, E>[] arr, final int pos, final int len) {
      final int p = ((start % CAP) + CAP) % CAP, k = CAP - p;
      if(len <= k) {
        System.arraycopy(nodes, p, arr, pos, len);
      } else {
        System.arraycopy(nodes, p, arr, pos, k);
        System.arraycopy(nodes, 0, arr, pos + k, len - k);
      }
    }
  }
}
