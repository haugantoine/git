import static org.eclipse.jgit.diff.DiffEntry.ChangeType.ADD;
import static org.eclipse.jgit.diff.DiffEntry.ChangeType.COPY;
import static org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE;
import static org.eclipse.jgit.diff.DiffEntry.ChangeType.MODIFY;
import static org.eclipse.jgit.diff.DiffEntry.ChangeType.RENAME;
import static org.eclipse.jgit.diff.DiffEntry.Side.NEW;
import static org.eclipse.jgit.diff.DiffEntry.Side.OLD;
import java.util.Collection;
import java.util.Collections;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.revwalk.FollowFilter;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.NotIgnoredFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
 * Format a Git style patch script.
	private static final int DEFAULT_BINARY_FILE_THRESHOLD = PackConfig.DEFAULT_BIG_FILE_THRESHOLD;

	/** Magic return content indicating it is empty or no content present. */
	private static final byte[] EMPTY = new byte[] {};

	/** Magic return indicating the content is binary. */
	private static final byte[] BINARY = new byte[] {};

	private ObjectReader reader;

	private int context = 3;

	private int abbreviationLength = 7;

	private DiffAlgorithm diffAlgorithm = MyersDiff.INSTANCE;

	private RawTextComparator comparator = RawTextComparator.DEFAULT;

	private int binaryFileThreshold = DEFAULT_BINARY_FILE_THRESHOLD;
	private String oldPrefix = "a/";
	private String newPrefix = "b/";
	private TreeFilter pathFilter = TreeFilter.ALL;

	private RenameDetector renameDetector;

	private ProgressMonitor progressMonitor;

	private ContentSource.Pair source;
	 * Once a repository has been set, the formatter must be released to ensure
	 * the internal ObjectReader is able to release its resources.
	 *
		if (reader != null)
			reader.release();

		reader = db.newObjectReader();
		ContentSource cs = ContentSource.create(reader);
		source = new ContentSource.Pair(cs, cs);

		DiffConfig dc = db.getConfig().get(DiffConfig.KEY);
		if (dc.isNoPrefix()) {
			setOldPrefix("");
			setNewPrefix("");
		}
		setDetectRenames(dc.isRenameDetectionEnabled());
	 * Set the algorithm that constructs difference output.
	 *
	 * @param alg
	 *            the algorithm to produce text file differences.
	 * @see MyersDiff#INSTANCE
	public void setDiffAlgorithm(DiffAlgorithm alg) {
		diffAlgorithm = alg;
	 * Set the line equivalence function for text file differences.
	 *
	 * @param cmp
	 *            The equivalence function used to determine if two lines of
	 *            text are identical. The function can be changed to ignore
	 *            various types of whitespace.
	 * @see RawTextComparator#DEFAULT
	 * @see RawTextComparator#WS_IGNORE_ALL
	 * @see RawTextComparator#WS_IGNORE_CHANGE
	 * @see RawTextComparator#WS_IGNORE_LEADING
	 * @see RawTextComparator#WS_IGNORE_TRAILING
	 */
	public void setDiffComparator(RawTextComparator cmp) {
		comparator = cmp;
	}

	/**
	 * Set maximum file size for text files.
	 *
	 * Files larger than this size will be treated as though they are binary and
	 * not text. Default is {@value #DEFAULT_BINARY_FILE_THRESHOLD} .
	 *
	 * @param threshold
	 *            the limit, in bytes. Files larger than this size will be
	 *            assumed to be binary, even if they aren't.
	 */
	public void setBinaryFileThreshold(int threshold) {
		this.binaryFileThreshold = threshold;
	}

	/**
	 * Set the prefix applied in front of old file paths.
	 * @param prefix
	 *            the prefix in front of old paths. Typically this is the
	 *            standard string {@code "a/"}, but may be any prefix desired by
	 *            the caller. Must not be null. Use the empty string to have no
	 *            prefix at all.
	 */
	public void setOldPrefix(String prefix) {
		oldPrefix = prefix;
	}

	/**
	 * Set the prefix applied in front of new file paths.
	 *
	 * @param prefix
	 *            the prefix in front of new paths. Typically this is the
	 *            standard string {@code "b/"}, but may be any prefix desired by
	 *            the caller. Must not be null. Use the empty string to have no
	 *            prefix at all.
	public void setNewPrefix(String prefix) {
		newPrefix = prefix;
	}

	/** @return true if rename detection is enabled. */
	public boolean isDetectRenames() {
		return renameDetector != null;
	}

	/**
	 * Enable or disable rename detection.
	 *
	 * Before enabling rename detection the repository must be set with
	 * {@link #setRepository(Repository)}. Once enabled the detector can be
	 * configured away from its defaults by obtaining the instance directly from
	 * {@link #getRenameDetector()} and invoking configuration.
	 *
	 * @param on
	 *            if rename detection should be enabled.
	 */
	public void setDetectRenames(boolean on) {
		if (on && renameDetector == null) {
			assertHaveRepository();
			renameDetector = new RenameDetector(db);
		} else if (!on)
			renameDetector = null;
	}

	/** @return the rename detector if rename detection is enabled. */
	public RenameDetector getRenameDetector() {
		return renameDetector;
	}

	/**
	 * Set the progress monitor for long running rename detection.
	 *
	 * @param pm
	 *            progress monitor to receive rename detection status through.
	 */
	public void setProgressMonitor(ProgressMonitor pm) {
		progressMonitor = pm;
	}

	/**
	 * Set the filter to produce only specific paths.
	 *
	 * If the filter is an instance of {@link FollowFilter}, the filter path
	 * will be updated during successive scan or format invocations. The updated
	 * path can be obtained from {@link #getPathFilter()}.
	 *
	 * @param filter
	 *            the tree filter to apply.
	 */
	public void setPathFilter(TreeFilter filter) {
		pathFilter = filter != null ? filter : TreeFilter.ALL;
	}

	/** @return the current path filter. */
	public TreeFilter getPathFilter() {
		return pathFilter;
	/** Release the internal ObjectReader state. */
	public void release() {
		if (reader != null)
			reader.release();
	}

	/**
	 * Determine the differences between two trees.
	 *
	 * No output is created, instead only the file paths that are different are
	 * returned. Callers may choose to format these paths themselves, or convert
	 * them into {@link FileHeader} instances with a complete edit list by
	 * calling {@link #toFileHeader(DiffEntry)}.
	 *
	 * @param a
	 *            the old (or previous) side.
	 * @param b
	 *            the new (or updated) side.
	 * @return the paths that are different.
	 * @throws IOException
	 *             trees cannot be read or file contents cannot be read.
	 */
	public List<DiffEntry> scan(AnyObjectId a, AnyObjectId b)
			throws IOException {
		assertHaveRepository();

		RevWalk rw = new RevWalk(reader);
		return scan(rw.parseTree(a), rw.parseTree(b));
	}

	/**
	 * Determine the differences between two trees.
	 *
	 * No output is created, instead only the file paths that are different are
	 * returned. Callers may choose to format these paths themselves, or convert
	 * them into {@link FileHeader} instances with a complete edit list by
	 * calling {@link #toFileHeader(DiffEntry)}.
	 *
	 * @param a
	 *            the old (or previous) side.
	 * @param b
	 *            the new (or updated) side.
	 * @return the paths that are different.
	 * @throws IOException
	 *             trees cannot be read or file contents cannot be read.
	 */
	public List<DiffEntry> scan(RevTree a, RevTree b) throws IOException {
		assertHaveRepository();

		CanonicalTreeParser aParser = new CanonicalTreeParser();
		CanonicalTreeParser bParser = new CanonicalTreeParser();

		aParser.reset(reader, a);
		bParser.reset(reader, b);

		return scan(aParser, bParser);
	}

	/**
	 * Determine the differences between two trees.
	 *
	 * No output is created, instead only the file paths that are different are
	 * returned. Callers may choose to format these paths themselves, or convert
	 * them into {@link FileHeader} instances with a complete edit list by
	 * calling {@link #toFileHeader(DiffEntry)}.
	 *
	 * @param a
	 *            the old (or previous) side.
	 * @param b
	 *            the new (or updated) side.
	 * @return the paths that are different.
	 * @throws IOException
	 *             trees cannot be read or file contents cannot be read.
	 */
	public List<DiffEntry> scan(AbstractTreeIterator a, AbstractTreeIterator b)
			throws IOException {
		assertHaveRepository();

		TreeWalk walk = new TreeWalk(reader);
		walk.reset();
		walk.addTree(a);
		walk.addTree(b);
		walk.setRecursive(true);

		TreeFilter filter = pathFilter;

		if (a instanceof WorkingTreeIterator)
			filter = AndTreeFilter.create(filter, new NotIgnoredFilter(0));
		if (b instanceof WorkingTreeIterator)
			filter = AndTreeFilter.create(filter, new NotIgnoredFilter(1));
		if (!(pathFilter instanceof FollowFilter))
			filter = AndTreeFilter.create(filter, TreeFilter.ANY_DIFF);
		walk.setFilter(filter);

		source = new ContentSource.Pair(source(a), source(b));

		List<DiffEntry> files = DiffEntry.scan(walk);
		if (pathFilter instanceof FollowFilter && isAdd(files)) {
			// The file we are following was added here, find where it
			// came from so we can properly show the rename or copy,
			// then continue digging backwards.
			//
			a.reset();
			b.reset();
			walk.reset();
			walk.addTree(a);
			walk.addTree(b);

			filter = TreeFilter.ANY_DIFF;
			if (a instanceof WorkingTreeIterator)
				filter = AndTreeFilter.create(new NotIgnoredFilter(0), filter);
			if (b instanceof WorkingTreeIterator)
				filter = AndTreeFilter.create(new NotIgnoredFilter(1), filter);
			walk.setFilter(filter);

			if (renameDetector == null)
				setDetectRenames(true);
			files = updateFollowFilter(detectRenames(DiffEntry.scan(walk)));

		} else if (renameDetector != null)
			files = detectRenames(files);

		return files;
	}

	private ContentSource source(AbstractTreeIterator iterator) {
		if (iterator instanceof WorkingTreeIterator)
			return ContentSource.create((WorkingTreeIterator) iterator);
		return ContentSource.create(reader);
	}

	private List<DiffEntry> detectRenames(List<DiffEntry> files)
			throws IOException {
		renameDetector.reset();
		renameDetector.addAll(files);
		return renameDetector.compute(reader, progressMonitor);
	}

	private boolean isAdd(List<DiffEntry> files) {
		String oldPath = ((FollowFilter) pathFilter).getPath();
		for (DiffEntry ent : files) {
			if (ent.getChangeType() == ADD && ent.getNewPath().equals(oldPath))
				return true;
		}
		return false;
	}

	private List<DiffEntry> updateFollowFilter(List<DiffEntry> files) {
		String oldPath = ((FollowFilter) pathFilter).getPath();
		for (DiffEntry ent : files) {
			if (isRename(ent) && ent.getNewPath().equals(oldPath)) {
				pathFilter = FollowFilter.create(ent.getOldPath());
				return Collections.singletonList(ent);
			}
		}
		return Collections.emptyList();
	}

	private static boolean isRename(DiffEntry ent) {
		return ent.getChangeType() == RENAME || ent.getChangeType() == COPY;
	}

	/**
	 * Format the differences between two trees.
	 *
	 * The patch is expressed as instructions to modify {@code a} to make it
	 * {@code b}.
	 *
	 * @param a
	 *            the old (or previous) side.
	 * @param b
	 *            the new (or updated) side.
	 * @throws IOException
	 *             trees cannot be read, file contents cannot be read, or the
	 *             patch cannot be output.
	 */
	public void format(AnyObjectId a, AnyObjectId b) throws IOException {
		format(scan(a, b));
	}

	/**
	 * Format the differences between two trees.
	 *
	 * The patch is expressed as instructions to modify {@code a} to make it
	 * {@code b}.
	 *
	 * @param a
	 *            the old (or previous) side.
	 * @param b
	 *            the new (or updated) side.
	 * @throws IOException
	 *             trees cannot be read, file contents cannot be read, or the
	 *             patch cannot be output.
	 */
	public void format(RevTree a, RevTree b) throws IOException {
		format(scan(a, b));
	}

	/**
	 * Format the differences between two trees.
	 *
	 * The patch is expressed as instructions to modify {@code a} to make it
	 * {@code b}.
	 *
	 * @param a
	 *            the old (or previous) side.
	 * @param b
	 *            the new (or updated) side.
	 * @throws IOException
	 *             trees cannot be read, file contents cannot be read, or the
	 *             patch cannot be output.
	 */
	public void format(AbstractTreeIterator a, AbstractTreeIterator b)
			throws IOException {
		format(scan(a, b));
	}

		FormatResult res = createFormatResult(ent);
		format(res.header, res.a, res.b);
		return QuotedString.GIT_PATH.quote(name);
		if (head.getPatchType() == PatchType.UNIFIED)
			format(head.toEditList(), a, b);
	 * @param edits
	 *            some differences which have been calculated between A and B
	public void format(final EditList edits, final RawText a, final RawText b)
			throws IOException {
	public FileHeader toFileHeader(DiffEntry ent) throws IOException,
		return createFormatResult(ent).header;
	}

	private static class FormatResult {
		FileHeader header;

		RawText a;

		RawText b;
	}

	private FormatResult createFormatResult(DiffEntry ent) throws IOException,
			CorruptObjectException, MissingObjectException {
		final FormatResult res = new FormatResult();
		formatHeader(buf, ent);
			formatOldNewPaths(buf, ent);

			assertHaveRepository();
			byte[] aRaw = open(OLD, ent);
			byte[] bRaw = open(NEW, ent);

			if (aRaw == BINARY || bRaw == BINARY //
					|| RawText.isBinary(aRaw) || RawText.isBinary(bRaw)) {
				formatOldNewPaths(buf, ent);

				res.a = new RawText(comparator, aRaw);
				res.b = new RawText(comparator, bRaw);
				editList = diff(res.a, res.b);

				switch (ent.getChangeType()) {
				case RENAME:
				case COPY:
					if (!editList.isEmpty())
						formatOldNewPaths(buf, ent);
					break;

				default:
					formatOldNewPaths(buf, ent);
					break;
				}
			}
		}

		res.header = new FileHeader(buf.toByteArray(), editList, type);
		return res;
	}

	private EditList diff(RawText a, RawText b) {
		return diffAlgorithm.diff(comparator, a, b);
	}

	private void assertHaveRepository() {
		if (db == null)
			throw new IllegalStateException(JGitText.get().repositoryIsRequired);
	}

	private byte[] open(DiffEntry.Side side, DiffEntry entry)
			throws IOException {
		if (entry.getMode(side) == FileMode.MISSING)
			return EMPTY;

		if (entry.getMode(side).getObjectType() != Constants.OBJ_BLOB)
			return EMPTY;

		if (isBinary(entry.getPath(side)))
			return BINARY;

		AbbreviatedObjectId id = entry.getId(side);
		if (!id.isComplete()) {
			Collection<ObjectId> ids = reader.resolve(id);
			if (ids.size() == 1) {
				id = AbbreviatedObjectId.fromObjectId(ids.iterator().next());
				switch (side) {
				case OLD:
					entry.oldId = id;
					break;
				case NEW:
					entry.newId = id;
					break;
				}
			} else if (ids.size() == 0)
				throw new MissingObjectException(id, Constants.OBJ_BLOB);
			else
				throw new AmbiguousObjectException(id, ids);
		}

		try {
			ObjectLoader ldr = source.open(side, entry);
			return ldr.getBytes(binaryFileThreshold);

		} catch (LargeObjectException.ExceedsLimit overLimit) {
			return BINARY;

		} catch (LargeObjectException.ExceedsByteArrayLimit overLimit) {
			return BINARY;

		} catch (LargeObjectException.OutOfMemory tooBig) {
			return BINARY;

		} catch (LargeObjectException tooBig) {
			tooBig.setObjectId(id.toObjectId());
			throw tooBig;
		}
	}

	private boolean isBinary(String path) {
		return false;
	}

	private void formatHeader(ByteArrayOutputStream o, DiffEntry ent)
			throws IOException {
		final ChangeType type = ent.getChangeType();
		final String oldp = ent.getOldPath();
		final String newp = ent.getNewPath();
		final FileMode oldMode = ent.getOldMode();
		final FileMode newMode = ent.getNewMode();

		o.write(encodeASCII("diff --git "));
		o.write(encode(quotePath(oldPrefix + (type == ADD ? newp : oldp))));
		o.write(' ');
		o.write(encode(quotePath(newPrefix + (type == DELETE ? oldp : newp))));
		o.write('\n');

		switch (type) {
		case ADD:
			o.write(encodeASCII("new file mode "));
			newMode.copyTo(o);
			o.write('\n');
			break;

		case DELETE:
			o.write(encodeASCII("deleted file mode "));
			oldMode.copyTo(o);
			o.write('\n');
			break;

		case RENAME:
			o.write(encodeASCII("similarity index " + ent.getScore() + "%"));
			o.write('\n');

			o.write(encode("rename from " + quotePath(oldp)));
			o.write('\n');

			o.write(encode("rename to " + quotePath(newp)));
			o.write('\n');
			break;

		case COPY:
			o.write(encodeASCII("similarity index " + ent.getScore() + "%"));
			o.write('\n');

			o.write(encode("copy from " + quotePath(oldp)));
			o.write('\n');

			o.write(encode("copy to " + quotePath(newp)));
			o.write('\n');

			if (!oldMode.equals(newMode)) {
				o.write(encodeASCII("new file mode "));
				newMode.copyTo(o);
				o.write('\n');
			break;

		case MODIFY:
			if (0 < ent.getScore()) {
				o.write(encodeASCII("dissimilarity index "
						+ (100 - ent.getScore()) + "%"));
				o.write('\n');
			}
			break;
		}

		if ((type == MODIFY || type == RENAME) && !oldMode.equals(newMode)) {
			o.write(encodeASCII("old mode "));
			oldMode.copyTo(o);
			o.write('\n');

			o.write(encodeASCII("new mode "));
			newMode.copyTo(o);
			o.write('\n');
		}

		if (!ent.getOldId().equals(ent.getNewId())) {
			o.write(encodeASCII("index " //
					+ format(ent.getOldId()) //
					+ ".." //
					+ format(ent.getNewId())));
			if (oldMode.equals(newMode)) {
				o.write(' ');
				newMode.copyTo(o);
			}
			o.write('\n');
		}
	}

	private void formatOldNewPaths(ByteArrayOutputStream o, DiffEntry ent)
			throws IOException {
		final String oldp;
		final String newp;

		switch (ent.getChangeType()) {
		case ADD:
			oldp = DiffEntry.DEV_NULL;
			newp = quotePath(newPrefix + ent.getNewPath());
			break;

		case DELETE:
			oldp = quotePath(oldPrefix + ent.getOldPath());
			newp = DiffEntry.DEV_NULL;
			break;

		default:
			oldp = quotePath(oldPrefix + ent.getOldPath());
			newp = quotePath(newPrefix + ent.getNewPath());
			break;
		o.write(encode("--- " + oldp + "\n"));
		o.write(encode("+++ " + newp + "\n"));