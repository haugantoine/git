/*
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2008-2010, Google Inc.
 * Copyright (C) 2006-2010, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org>
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.internal.storage.file;

import static org.eclipse.jgit.lib.Constants.DOT_GIT;
import static org.eclipse.jgit.lib.Constants.GIT_ALTERNATE_OBJECT_DIRECTORIES_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_CEILING_DIRECTORIES_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_DIR_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_INDEX_FILE_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_OBJECT_DIRECTORY_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_WORK_TREE_KEY;
import static org.eclipse.jgit.lib.RefDatabase.ALL;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import org.eclipse.jgit.attributes.AttributesNode;
import org.eclipse.jgit.attributes.AttributesNodeProvider;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.events.ConfigChangedEvent;
import org.eclipse.jgit.events.ConfigChangedListener;
import org.eclipse.jgit.events.IndexChangedEvent;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.ObjectDirectory.AlternateHandle;
import org.eclipse.jgit.internal.storage.file.ObjectDirectory.AlternateRepository;
import org.eclipse.jgit.internal.storage.reftree.RefTreeDatabase;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.CoreConfig.HideDotFiles;
import org.eclipse.jgit.lib.CoreConfig.SymLinks;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.StringUtils;
import org.eclipse.jgit.util.SystemReader;

/**
 * Represents a Git repository. A repository holds all objects and refs used for
 * managing source code (could by any type of file, but source code is what
 * SCM's are typically used for).
 *
 * In Git terms all data is stored in GIT_DIR, typically a directory called
 * .git. A work tree is maintained unless the repository is a bare repository.
 * Typically the .git directory is located at the root of the work dir.
 *
 * <ul>
 * <li>GIT_DIR
 * <ul>
 * <li>objects/ - objects</li>
 * <li>refs/ - tags and heads</li>
 * <li>config - configuration</li>
 * <li>info/ - more configurations</li>
 * </ul>
 * </li>
 * </ul>
 * <p>
 * This class is thread-safe.
 * <p>
 * This implementation only handles a subtly undocumented subset of git
 * features.
 *
 */
public class FileRepository extends Repository {
	public static FileRepository createRepository(File indexFile, File objDir,
			File altObjDir, File theDir) throws IOException {
		return new FileRepository(indexFile, objDir, altObjDir,
				theDir); //
	}

	public static FileRepository createGitDirRepo(File dir) throws IOException {
		FileRepository r = new FileRepository(); //
		if (RepositoryCache.FileKey.isGitRepository(dir))
			r.setGitDir(dir);
		r.findGitDir(dir);
		r.setup();
		return r;
	}

	public static FileRepository createWorkTreeRepository(File worktree)
			throws IOException {
		FileRepository r = new FileRepository(); //
		r.setWorkTree(worktree);
		r.setup();
		return r;
	}

	public static FileRepository createGitDirEnvRepository(String aGitdir)
			throws IOException {

		FileRepository r = new FileRepository(); //
		r.setGitDir(aGitdir != null ? new File(aGitdir) : null); //
		r.readEnvironment(); //
		r.findGitDir(new File("").getAbsoluteFile()); //$NON-NLS-1$
		if (r.gitDir == null)
			throw new IOException();
		r.setup();
		return r;
	}

	private FileBasedConfig systemConfig;

	private FileBasedConfig userConfig;

	private FileBasedConfig repoConfig;

	private RefDatabase refs;

	private ObjectDirectory objectDatabase;

	private FileSnapshot snapshot;

	public FileRepository() {
	}

	/**
	 * Construct a representation of a Git repository.
	 * <p>
	 * The work tree, object directory, alternate object directories and index
	 * file locations are deduced from the given git directory and the default
	 * rules by running {@link FileRepositoryBuilder}. This constructor is the
	 * same as saying:
	 *
	 * <pre>
	 * new FileRepositoryBuilder().setGitDir(gitDir).build()
	 * </pre>
	 *
	 * @param gitDir
	 *            GIT_DIR (the location of the repository metadata).
	 * @throws IOException
	 *             the repository appears to already exist but cannot be
	 *             accessed.
	 * @see FileRepositoryBuilder
	 */
	public FileRepository(final File gitDir) throws IOException {
		this(null, null, null, gitDir);
	}

	public FileRepository(File directory, File gitdir, boolean isBare)
			throws IOException {
		this.bare = isBare;
		SystemReader sr = SystemReader.getInstance();
		if (!this.bare) {
			getWorkTree(directory, gitdir, sr);
		}
		getGitDir(directory, gitdir, sr);

		String val = sr.getenv(GIT_OBJECT_DIRECTORY_KEY);
		if (val != null)
			this.objectDirectory = new File(val);

		val = sr.getenv(GIT_ALTERNATE_OBJECT_DIRECTORIES_KEY);
		if (val != null) {
			for (String path : val.split(File.pathSeparator)) {
				File other = new File(path);
				if (alternateObjectDirectories == null)
					alternateObjectDirectories = new LinkedList<File>();
				alternateObjectDirectories.add(other);
			}
		}

		val = sr.getenv(GIT_INDEX_FILE_KEY);
		if (!isBare && val != null)
			this.indexFile = new File(val);

		val = sr.getenv(GIT_CEILING_DIRECTORIES_KEY);
		if (val != null) {
			for (String path : val.split(File.pathSeparator)) {
				File root = new File(path);
				if (ceilingDirectories == null)
					ceilingDirectories = new LinkedList<File>();
				ceilingDirectories.add(root);
			}
		}

		// If we aren't bare, we should have a work tree.
		//
		if (!this.bare && workTree == null)
			workTree = guessWorkTreeOrFail();

		if (!bare && indexFile == null)
			this.indexFile = new File(gitDir, "index"); //$NON-NLS-1$

		if (objectDirectory == null && gitDir != null)
			this.objectDirectory = FS.DETECTED.resolve(gitDir, "objects"); //$NON-NLS-1$

		if (StringUtils.isEmptyOrNull(SystemReader.getInstance()
				.getenv(Constants.GIT_CONFIG_NOSYSTEM_KEY))) {
			File configFile = FS.DETECTED.getGitSystemConfig();
			if (configFile == null) {
				systemConfig = getEmptySystemConfig();
			} else {
				systemConfig = new FileBasedConfig(null, configFile);
			}
		}
		else
			systemConfig = getEmptySystemConfig();
		File cfgLocation = new File(FS.DETECTED.userHome(), ".gitconfig");//$NON-NLS-1$
		File repoConfigFile = FS.DETECTED.resolve(getDirectory(),
				Constants.CONFIG);

		userConfig = new FileBasedConfig(systemConfig, cfgLocation);
		repoConfig = new FileBasedConfig(userConfig,
				repoConfigFile);

		loadSystemConfig();
		loadUserConfig();
		loadRepoConfig();

		repoConfig.addChangeListener(new ConfigChangedListener() {
			public void onConfigChanged(ConfigChangedEvent event) {
				fireEvent(event);
			}
		});

		final long repositoryFormatVersion = getConfig().getLong(
				ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_REPO_FORMAT_VERSION, 0);

		String reftype = repoConfig.getString("extensions", null, //$NON-NLS-1$
				"refsStorage"); //$NON-NLS-1$
		if (repositoryFormatVersion >= 1 && reftype != null) {
			if (StringUtils.equalsIgnoreCase(reftype, "reftree")) { //$NON-NLS-1$
				refs = new RefTreeDatabase(this, new RefDirectory(this));
			} else {
				throw new IOException(JGitText.get().unknownRepositoryFormat);
			}
		} else {
			refs = new RefDirectory(this);
		}

		objectDatabase = new ObjectDirectory(repoConfig, //
				getObjectDirectory(), //
				getAlternateObjectDirectories(), //
				new File(getDirectory(), Constants.SHALLOW));

		if (objectDatabase.exists() && repositoryFormatVersion > 1)
			throw new IOException(MessageFormat.format(
					JGitText.get().unknownRepositoryFormat2,
					Long.valueOf(repositoryFormatVersion)));

		if (!isBare())
			snapshot = FileSnapshot.save(getIndexFile());
	}

	private FileBasedConfig getEmptySystemConfig() {
		return new FileBasedConfig(null) {
			public void load() {
				// empty, do not load
			}

			public boolean isOutdated() {
				// regular class would bomb here
				return false;
			}
		};
	}

	public FileRepository(File indexFile, File objDir, File altObjDir,
			File theDir) throws IOException {
		this.gitDir = theDir;
		this.objectDirectory = objDir;
		if (altObjDir != null) {
			if (alternateObjectDirectories == null)
				alternateObjectDirectories = new LinkedList<File>();
			alternateObjectDirectories.add(altObjDir);
		}
		this.indexFile = indexFile;
		setup();
	}

	private void getGitDir(File directory, File gitDir, SystemReader sr) {
		String val = sr.getenv(GIT_DIR_KEY);
		if (gitDir != null) {
			this.gitDir = gitDir;
		} else if (val != null) {
			this.gitDir = new File(val);
		} else {
			File dir = (directory == null) ? Repository.getUserDirectory()
					: directory;
			this.gitDir = (this.bare) ? dir : new File(dir, Constants.DOT_GIT);
		}
	}

	private void getWorkTree(File directory, File gitDir, SystemReader sr) {
		if (directory != null) {
			this.workTree = directory;
		} else if (gitDir == null) {
			String workTreePath = sr.getenv(GIT_WORK_TREE_KEY);
			if (workTreePath != null)
				this.workTree = new File(workTreePath);
		} else {
			this.workTree = Repository.getUserDirectory();
		}
	}

	/**
	 * Guess and populate all parameters not already defined.
	 * <p>
	 * If an option was not set, the setup method will try to default the option
	 * based on other options. If insufficient information is available, an
	 * exception is thrown to the caller.
	 *
	 * @return {@code this}
	 * @throws IllegalArgumentException
	 *             insufficient parameters were set, or some parameters are
	 *             incompatible with one another.
	 * @throws IOException
	 *             the repository could not be accessed to configure the rest of
	 *             the builder's parameters.
	 */
	public void setup() throws IOException {
		if (gitDir == null && workTree == null)
			throw new IllegalArgumentException(
					JGitText.get().eitherGitDirOrWorkTreeRequired);
		// No gitDir? Try to assume its under the workTree or a ref to
		// another
		// location
		if (gitDir == null) {
			File dotGit = new File(workTree, DOT_GIT);
			if (dotGit.isFile()) {
				this.gitDir = getSymRef(workTree, dotGit);
			} else {
				this.gitDir = dotGit;
			}
			this.config = null;
		}
		// If we aren't bare, we should have a work tree.
		//
		if (!bare && workTree == null)
			workTree = guessWorkTreeOrFail();

		if (!bare) {
			// If after guessing we're still not bare, we must have
			// a metadata directory to hold the repository. Assume
			// its at the work tree.
			//
			if (gitDir == null)
				setGitDir(workTree.getParentFile());
			if (indexFile == null)
				setIndexFile(new File(gitDir, "index")); //$NON-NLS-1$
		}
		if (getObjectDirectory() == null && gitDir != null)
			setObjectDirectory(FS.DETECTED.resolve(gitDir, "objects")); //$NON-NLS-1$

		if (StringUtils.isEmptyOrNull(SystemReader.getInstance()
				.getenv(Constants.GIT_CONFIG_NOSYSTEM_KEY)))
			systemConfig = SystemReader.getInstance().openSystemConfig(null);
		else
			systemConfig = new FileBasedConfig(null) {
				public void load() {
					// empty, do not load
				}

				public boolean isOutdated() {
					// regular class would bomb here
					return false;
				}
			};
		userConfig = SystemReader.getInstance().openUserConfig(systemConfig);
		repoConfig = new FileBasedConfig(userConfig,
				FS.DETECTED.resolve(getDirectory(), Constants.CONFIG));

		loadSystemConfig();
		loadUserConfig();
		loadRepoConfig();

		repoConfig.addChangeListener(new ConfigChangedListener() {
			public void onConfigChanged(ConfigChangedEvent event) {
				fireEvent(event);
			}
		});

		final long repositoryFormatVersion = getConfig().getLong(
				ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_REPO_FORMAT_VERSION, 0);

		String reftype = repoConfig.getString("extensions", null, //$NON-NLS-1$
				"refsStorage"); //$NON-NLS-1$
		if (repositoryFormatVersion >= 1 && reftype != null) {
			if (StringUtils.equalsIgnoreCase(reftype, "reftree")) { //$NON-NLS-1$
				refs = new RefTreeDatabase(this, new RefDirectory(this));
			} else {
				throw new IOException(JGitText.get().unknownRepositoryFormat);
			}
		} else {
			refs = new RefDirectory(this);
		}

		objectDatabase = new ObjectDirectory(repoConfig, //
				getObjectDirectory(), //
				getAlternateObjectDirectories(), //
				new File(getDirectory(), Constants.SHALLOW));

		if (objectDatabase.exists() && repositoryFormatVersion > 1)
			throw new IOException(MessageFormat.format(
					JGitText.get().unknownRepositoryFormat2,
					Long.valueOf(repositoryFormatVersion)));

		if (!isBare())
			snapshot = FileSnapshot.save(getIndexFile());
	}

	private void loadSystemConfig() throws IOException {
		try {
			systemConfig.load();
		} catch (ConfigInvalidException e1) {
			IOException e2 = new IOException(
					MessageFormat.format(JGitText.get().systemConfigFileInvalid,
							systemConfig.getFile().getAbsolutePath(), e1));
			e2.initCause(e1);
			throw e2;
		}
	}

	private void loadUserConfig() throws IOException {
		try {
			userConfig.load();
		} catch (ConfigInvalidException e1) {
			IOException e2 = new IOException(
					MessageFormat.format(JGitText.get().userConfigFileInvalid,
							userConfig.getFile().getAbsolutePath(), e1));
			e2.initCause(e1);
			throw e2;
		}
	}

	private void loadRepoConfig() throws IOException {
		try {
			repoConfig.load();
		} catch (ConfigInvalidException e1) {
			IOException e2 = new IOException(
					JGitText.get().unknownRepositoryFormat);
			e2.initCause(e1);
			throw e2;
		}
	}

	/**
	 * Create a new Git repository initializing the necessary files and
	 * directories.
	 *
	 * @param bare
	 *            if true, a bare repository is created.
	 *
	 * @throws IOException
	 *             in case of IO problem
	 */
	public void create() throws IOException {
		final FileBasedConfig cfg = getConfig();
		if (cfg.getFile().exists()) {
			throw new IllegalStateException(MessageFormat.format(
					JGitText.get().repositoryAlreadyExists, getDirectory()));
		}
		FileUtils.mkdirs(getDirectory(), true);
		HideDotFiles hideDotFiles = getConfig().getEnum(
				ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_HIDEDOTFILES,
				HideDotFiles.DOTGITONLY);
		if (hideDotFiles != HideDotFiles.FALSE && !bare
				&& getDirectory().getName().startsWith(".")) //$NON-NLS-1$
			FS.DETECTED.setHidden(getDirectory(), true);
		refs.create();
		objectDatabase.create();

		FileUtils.mkdir(new File(getDirectory(), "branches")); //$NON-NLS-1$
		FileUtils.mkdir(new File(getDirectory(), "hooks")); //$NON-NLS-1$

		RefUpdate head = updateRef(Constants.HEAD);
		head.disableRefLog();
		head.link(Constants.R_HEADS + Constants.MASTER);

		final boolean fileMode = getFileMode();

		SymLinks symLinks = SymLinks.FALSE;
		if (FS.DETECTED.supportsSymlinks()) {
			File tmp = new File(getDirectory(), "tmplink"); //$NON-NLS-1$
			try {
				FS.DETECTED.createSymLink(tmp, "target"); //$NON-NLS-1$
				symLinks = null;
				FileUtils.delete(tmp);
			} catch (IOException e) {
				// Normally a java.nio.file.FileSystemException
			}
		}
		if (symLinks != null)
			cfg.setString(ConfigConstants.CONFIG_CORE_SECTION, null,
					ConfigConstants.CONFIG_KEY_SYMLINKS,
					symLinks.name().toLowerCase());
		cfg.setInt(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_REPO_FORMAT_VERSION, 0);
		cfg.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_FILEMODE, fileMode);
		if (bare)
			cfg.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
					ConfigConstants.CONFIG_KEY_BARE, true);
		cfg.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_LOGALLREFUPDATES, !bare);
		if (SystemReader.getInstance().isMacOS())
			// Java has no other way
			cfg.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
					ConfigConstants.CONFIG_KEY_PRECOMPOSEUNICODE, true);
		if (!bare) {
			File workTree = getWorkTree();
			if (!getDirectory().getParentFile().equals(workTree)) {
				cfg.setString(ConfigConstants.CONFIG_CORE_SECTION, null,
						ConfigConstants.CONFIG_KEY_WORKTREE,
						getWorkTree().getAbsolutePath());
				LockFile dotGitLockFile = new LockFile(
						new File(workTree, Constants.DOT_GIT));
				try {
					if (dotGitLockFile.lock()) {
						dotGitLockFile.write(Constants.encode(Constants.GITDIR
								+ getDirectory().getAbsolutePath()));
						dotGitLockFile.commit();
					}
				} finally {
					dotGitLockFile.unlock();
				}
			}
		}
		cfg.save();
	}

	private boolean getFileMode() throws IOException {
		final boolean fileMode;
		if (FS.DETECTED.supportsExecute()) {
			File tmp = File.createTempFile("try", "execute", getDirectory()); //$NON-NLS-1$ //$NON-NLS-2$

			FS.DETECTED.setExecute(tmp, true);
			final boolean on = FS.DETECTED.canExecute(tmp);

			FS.DETECTED.setExecute(tmp, false);
			final boolean off = FS.DETECTED.canExecute(tmp);
			FileUtils.delete(tmp);

			fileMode = on && !off;
		} else {
			fileMode = false;
		}
		return fileMode;
	}

	/**
	 * @return the directory containing the objects owned by this repository.
	 */
	public File getObjectsDirectory() {
		return objectDatabase.getDirectory();
	}

	/**
	 * @return the object database which stores this repository's data.
	 */
	public ObjectDirectory getObjectDatabase() {
		return objectDatabase;
	}

	/** @return the reference database which stores the reference namespace. */
	public RefDatabase getRefDatabase() {
		return refs;
	}

	/**
	 * @return the configuration of this repository
	 */
	public FileBasedConfig getConfig() {
		if (systemConfig.isOutdated()) {
			try {
				loadSystemConfig();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		if (userConfig.isOutdated()) {
			try {
				loadUserConfig();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		if (repoConfig.isOutdated()) {
			try {
				loadRepoConfig();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return repoConfig;
	}

	/**
	 * Objects known to exist but not expressed by {@link #getAllRefs()}.
	 * <p>
	 * When a repository borrows objects from another repository, it can
	 * advertise that it safely has that other repository's references, without
	 * exposing any other details about the other repository. This may help a
	 * client trying to push changes avoid pushing more than it needs to.
	 *
	 * @return unmodifiable collection of other known objects.
	 */
	public Set<ObjectId> getAdditionalHaves() {
		HashSet<ObjectId> r = new HashSet<ObjectId>();
		for (AlternateHandle d : objectDatabase.myAlternates()) {
			if (d instanceof AlternateRepository) {
				Repository repo;

				repo = ((AlternateRepository) d).repository;
				for (Ref ref : repo.getAllRefs().values()) {
					if (ref.getObjectId() != null)
						r.add(ref.getObjectId());
					if (ref.getPeeledObjectId() != null)
						r.add(ref.getPeeledObjectId());
				}
				r.addAll(repo.getAdditionalHaves());
			}
		}
		return r;
	}

	/**
	 * Add a single existing pack to the list of available pack files.
	 *
	 * @param pack
	 *            path of the pack file to open.
	 * @throws IOException
	 *             index file could not be opened, read, or is not recognized as
	 *             a Git pack file index.
	 */
	public void openPack(final File pack) throws IOException {
		objectDatabase.openPack(pack);
	}

	@Override
	public void scanForRepoChanges() throws IOException {
		getRefDatabase().getRefs(ALL); // This will look for changes to refs
		detectIndexChanges();
	}

	/**
	 * Detect index changes.
	 */
	private void detectIndexChanges() {
		if (isBare())
			return;

		if (snapshot == null)
			snapshot = FileSnapshot.save(indexFile);
		else if (snapshot.isModified(indexFile)) {
			snapshot = FileSnapshot.save(indexFile);
			fireEvent(new IndexChangedEvent());
		}
	}

	@Override
	public void notifyIndexChanged() {
		snapshot = FileSnapshot.save(getIndexFile());
		fireEvent(new IndexChangedEvent());
	}

	/**
	 * @param refName
	 * @return a {@link ReflogReader} for the supplied refname, or null if the
	 *         named ref does not exist.
	 * @throws IOException
	 *             the ref could not be accessed.
	 */
	public ReflogReader getReflogReader(String refName) throws IOException {
		Ref ref = findRef(refName);
		if (ref != null)
			return new ReflogReaderImpl(this, ref.getName());
		return null;
	}

	@Override
	public AttributesNodeProvider createAttributesNodeProvider() {
		return new AttributesNodeProviderImpl(this);
	}

	/**
	 * Implementation a {@link AttributesNodeProvider} for a
	 * {@link FileRepository}.
	 *
	 * @author <a href="mailto:arthur.daussy@obeo.fr">Arthur Daussy</a>
	 *
	 */
	static class AttributesNodeProviderImpl implements AttributesNodeProvider {

		private AttributesNode infoAttributesNode;

		private AttributesNode globalAttributesNode;

		/**
		 * Constructor.
		 *
		 * @param repo
		 *            {@link Repository} that will provide the attribute nodes.
		 */
		protected AttributesNodeProviderImpl(Repository repo) {
			infoAttributesNode = new InfoAttributesNode(repo);
			globalAttributesNode = new GlobalAttributesNode(repo);
		}

		public AttributesNode getInfoAttributesNode() throws IOException {
			if (infoAttributesNode instanceof InfoAttributesNode)
				infoAttributesNode = ((InfoAttributesNode) infoAttributesNode)
						.load();
			return infoAttributesNode;
		}

		public AttributesNode getGlobalAttributesNode() throws IOException {
			if (globalAttributesNode instanceof GlobalAttributesNode)
				globalAttributesNode = ((GlobalAttributesNode) globalAttributesNode)
						.load();
			return globalAttributesNode;
		}

		static void loadRulesFromFile(AttributesNode r, File attrs)
				throws FileNotFoundException, IOException {
			if (attrs.exists()) {
				FileInputStream in = new FileInputStream(attrs);
				try {
					r.parse(in);
				} finally {
					in.close();
				}
			}
		}

	}

}
