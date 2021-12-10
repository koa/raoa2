import {Injectable} from '@angular/core';
import Dexie, {Table, WhereClause} from 'dexie';
import Semaphore from 'semaphore-async-await';

export interface AlbumData {
    id: string;
    title: string;
    albumVersion: string;
    fnchAlbumId: string;
    entryCount: number;
    albumTime: number;
}

export interface AlbumSettings {
    id: string;
    lastUpdated: number;
    albumEntryVersion: string;
    offlineSyncedVersion: string;
    syncOffline: boolean;
}

export interface AlbumEntryData {
    albumId: string;
    albumEntryId: string;
    name: string;
    targetWidth: number;
    targetHeight: number;
    created: number;
    keywords: string[];
    contentType: string;
    entryType: 'image' | 'video';
    cameraModel: string;
    exposureTime: number;
    fNumber: number;
    focalLength35: number;
    isoSpeedRatings: number;
}

export interface ImageBlob {
    albumId: string;
    albumEntryId: string;
    mediaSize: number;
    data: Blob;
}

interface PendingKeywordAddEntry {
    albumId: string;
    albumEntryId: string;
    keyword: string;
}

interface PendingKeywordRemoveEntry {
    albumId: string;
    albumEntryId: string;
    keyword: string;
}

export interface KeywordState {
    existingKeywords: Set<string>;
    pendingAddKeywords: Set<string>;
    pendingRemoveKeywords: Set<string>;
}

export interface UserPermissions {
    canManageUsers: boolean;
    canEdit: boolean;
}

export interface DeviceData {
    screenSize: number;
}


@Injectable({
    providedIn: 'root'
})
export class StorageService extends Dexie {
    constructor() {
        super('RaoaDatabase');
        this.version(6)
            .stores({
                albumData: 'id',
                albumSettings: 'id, offlineSyncedVersion',
                albumEntryData: '[albumId+albumEntryId], albumId, keywords, entryType, created',
                pendingKeywordAddData: '[albumId+albumEntryId+keyword], [albumId+albumEntryId], albumId',
                pendingKeywordRemoveData: '[albumId+albumEntryId+keyword], [albumId+albumEntryId], albumId',
                bigImages: '[albumId+albumEntryId], albumId',
                smallImages: '[albumId+albumEntryId], albumId',
                userPermissions: '++id',
                deviceData: '++id'
            });
        this.albumDataTable = this.table('albumData');
        this.albumSettingsTable = this.table('albumSettings');
        this.albumEntryDataTable = this.table('albumEntryData');
        this.pendingKeywordAddDataTable = this.table('pendingKeywordAddData');
        this.pendingKeywordRemoveDataTable = this.table('pendingKeywordRemoveData');
        this.bigImagesTable = this.table('bigImages');
        this.smallImagesTable = this.table('smallImages');
        this.userPermissionsTable = this.table('userPermissions');
        this.deviceDataTable = this.table('deviceData');

        this.whereAddPendingKeyword = this.pendingKeywordAddDataTable.where(['albumId', 'albumEntryId']);
        this.whereRemovePendingKeyword = this.pendingKeywordRemoveDataTable.where(['albumId', 'albumEntryId']);
        this.albumEntryByEntryAndAlbum = this.albumEntryDataTable.where(['albumId', 'albumEntryId']);

        this.getDeviceData().then(deviceData => {
            if (deviceData !== undefined) {
                this.screenSize = deviceData.screenSize;
                this.maxSmallScreenSize = this.screenSize / 4;
            }
        }).catch(er => console.error(er));
    }

    private readonly userPermissionsTable: Table<UserPermissions>;
    private readonly albumSettingsTable: Table<AlbumSettings, string>;
    private readonly albumDataTable: Table<AlbumData, string>;
    private readonly albumEntryDataTable: Table<AlbumEntryData, [string, string]>;
    private readonly pendingKeywordAddDataTable: Table<PendingKeywordAddEntry, [string, string, string]>;
    private readonly pendingKeywordRemoveDataTable: Table<PendingKeywordRemoveEntry, [string, string, string]>;
    private readonly smallImagesTable: Table<ImageBlob, [string, string, number]>;
    private readonly bigImagesTable: Table<ImageBlob, [string, string, number]>;
    private readonly deviceDataTable: Table<DeviceData>;

    private readonly whereAddPendingKeyword: WhereClause<PendingKeywordAddEntry, [string, string, string]>;
    private readonly whereRemovePendingKeyword: WhereClause<PendingKeywordRemoveEntry, [string, string, string]>;
    private readonly albumEntryByEntryAndAlbum: WhereClause<AlbumEntryData, [string, string]>;
    private readonly lastAccessTime = new Map<string, number>();
    private readonly cleanupSemaphore = new Semaphore(1);

    private screenSize = 3200;
    private maxSmallScreenSize = this.screenSize / 4;


    public async updateAlbumEntries(entries: AlbumEntryData[], albumId?: string, albumVersion?: string) {
        await this.transaction('rw',
            this.albumSettingsTable,
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                if (albumId) {
                    if (albumVersion) {
                        const settings = await this.albumSettingsTable.get(albumId);
                        if (settings) {
                            settings.albumEntryVersion = albumVersion;
                            settings.lastUpdated = Date.now();
                            await this.albumSettingsTable.put(settings);
                        } else {
                            await this.albumSettingsTable.put({
                                albumEntryVersion: albumVersion,
                                id: albumId,
                                lastUpdated: Date.now(),
                                offlineSyncedVersion: undefined,
                                syncOffline: false
                            });
                        }
                    }
                    const keepEntries = new Set<string>();
                    entries.forEach(entry => keepEntries.add(entry.albumEntryId));
                    await this.albumEntryDataTable
                        .where('albumId')
                        .equals(albumId)
                        .filter(entry => !keepEntries.has(entry.albumEntryId))
                        .delete();
                }
                await this.albumEntryDataTable.bulkPut(entries);
                for (const entry of entries) {
                    await this.adjustPendingKeywords(entry);
                }
            });
    }

    public listAlbums(): Promise<[AlbumData, AlbumSettings | undefined][]> {
        return this.transaction('r', this.albumDataTable, this.albumSettingsTable, async () => {
            const settings = new Map<string, AlbumSettings>();
            await this.albumSettingsTable.each(s => settings.set(s.id, s));
            const ret: [AlbumData, AlbumSettings][] = [];
            await this.albumDataTable.each(e => ret.push([e, settings.get(e.id)]));
            return ret;
        });
    }

    public getAlbum(albumId: string): Promise<AlbumData | undefined> {
        return this.transaction('r', this.albumDataTable, () => this.albumDataTable.get(albumId));
    }


    public async updateAlbums(albums: AlbumData[]) {
        await this.transaction('rw', this.albumDataTable, async () => {
            const dataBefore = new Map<string, AlbumData>();
            await this.albumDataTable.each(oldAlbumEntry => dataBefore.set(oldAlbumEntry.id, oldAlbumEntry));
            const modifiedAlbums: AlbumData[] = [];
            for (const newAlbumEntry of albums) {
                const albumEntryBefore = dataBefore.get(newAlbumEntry.id);
                if (albumEntryBefore === undefined) {
                    modifiedAlbums.push(newAlbumEntry);
                } else {
                    if (albumEntryBefore.albumVersion !== newAlbumEntry.albumVersion) {
                        modifiedAlbums.push(newAlbumEntry);
                    }
                }
            }
            this.albumDataTable.bulkPut(modifiedAlbums);
        });
    }

    public async keepAlbums(albumIds: string[]): Promise<string[]> {
        return this.transaction('rw',
            this.albumDataTable,
            this.albumSettingsTable,
            async () => {
                const albumsToRemove: string[] = [];
                await this.albumDataTable.where('id').noneOf(albumIds).eachPrimaryKey(albumId => {
                    albumsToRemove.push(albumId);
                });
                await Promise.all([
                    this.albumDataTable.where('id').noneOf(albumIds).delete(),
                    this.albumSettingsTable.where('id').noneOf(albumIds).delete()
                ]);
                return albumsToRemove;
            });
    }

    private async adjustPendingKeywords(entry: AlbumEntryData) {
        const keywords = new Set<string>(entry.keywords);
        await this.whereAddPendingKeyword
            .equals([entry.albumId, entry.albumEntryId])
            .each(addEntry => {
                if (keywords.has(addEntry.keyword)) {
                    return this.pendingKeywordAddDataTable.delete([entry.albumId, entry.albumEntryId, addEntry.keyword]);
                }
            });
        await this.whereRemovePendingKeyword
            .equals([entry.albumId, entry.albumEntryId])
            .each(removeEntry => {
                if (!keywords.has(removeEntry.keyword)) {
                    return this.pendingKeywordRemoveDataTable.delete([entry.albumId, entry.albumEntryId, removeEntry.keyword]);
                }
            });
    }

    public async listAlbum(albumId: string, filter?: (entry: AlbumEntryData) => boolean):
        Promise<[AlbumData | undefined, AlbumEntryData[] | undefined, AlbumSettings | undefined]> {
        this.lastAccessTime.set(albumId, Date.now());
        return this.transaction('r',
            [this.albumDataTable,
                this.albumSettingsTable,
                this.albumEntryDataTable,
                this.pendingKeywordAddDataTable,
                this.pendingKeywordRemoveDataTable],
            async (tx) => {
                try {
                    const [foundAlbumData, foundAlbumSettings] = await Promise.all([
                        await this.albumDataTable.get(albumId),
                        await this.albumSettingsTable.get(albumId)
                    ]);
                    if (foundAlbumData === undefined) {
                        return [undefined, undefined, undefined];
                    }
                    if (foundAlbumSettings?.albumEntryVersion === undefined
                        || foundAlbumSettings.albumEntryVersion !== foundAlbumData.albumVersion) {
                        return [foundAlbumData, undefined, foundAlbumSettings];
                    }
                    const entryFilter = filter ? filter : () => true;
                    const addKeywords = new Map<string, Set<string>>();
                    const removeKeywords = new Map<string, Set<string>>();
                    await Promise.all([
                        this.pendingKeywordAddDataTable.where('albumId').equals(albumId).each(entry => {
                            const albumEntryId = entry.albumEntryId;
                            const keyword = entry.keyword;
                            if (addKeywords.has(albumEntryId)) {
                                addKeywords.get(albumEntryId).add(keyword);
                            } else {
                                addKeywords.set(albumEntryId, new Set<string>([keyword]));
                            }
                        }),
                        this.pendingKeywordRemoveDataTable.where('albumId').equals(albumId).each(entry => {
                            const albumEntryId = entry.albumEntryId;
                            const keyword = entry.keyword;
                            if (removeKeywords.has(albumEntryId)) {
                                removeKeywords.get(albumEntryId).add(keyword);
                            } else {
                                removeKeywords.set(albumEntryId, new Set<string>([keyword]));
                            }
                        })]);
                    const finalList: AlbumEntryData[] = [];
                    (await this.albumEntryDataTable.where('albumId').equals(albumId).sortBy('created'))
                        .forEach(entry => {
                            const keywords = new Set<string>(entry.keywords);
                            if (addKeywords.has(entry.albumEntryId)) {
                                addKeywords.get(entry.albumEntryId).forEach(kw => keywords.add(kw));
                            }
                            if (removeKeywords.has(entry.albumEntryId)) {
                                removeKeywords.get(entry.albumEntryId).forEach(kw => keywords.delete(kw));
                            }
                            const kwList: string[] = [];
                            keywords.forEach(kw => kwList.push(kw));
                            const adjustedEntry = {
                                cameraModel: entry.cameraModel,
                                exposureTime: entry.exposureTime,
                                fNumber: entry.fNumber,
                                focalLength35: entry.focalLength35,
                                isoSpeedRatings: entry.isoSpeedRatings,
                                albumEntryId: entry.albumEntryId,
                                albumId: entry.albumId,
                                created: entry.created,
                                entryType: entry.entryType,
                                keywords: kwList,
                                name: entry.name,
                                targetHeight: entry.targetHeight,
                                targetWidth: entry.targetWidth,
                                contentType: entry.contentType
                            };
                            if (entryFilter(adjustedEntry)) {
                                finalList.push(adjustedEntry);
                            }
                        });
                    return [foundAlbumData, finalList, foundAlbumSettings];
                } catch (e) {
                    console.log('error', e);
                    throw e;
                }
            });
    }

    public async addKeyword(albumId: string, albumEntryIds: string[], keywords: string[]): Promise<void> {
        await this.transaction('rw',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                for (const albumEntryId of albumEntryIds) {
                    for (const keyword of keywords) {
                        await Promise.all(
                            [
                                this.pendingKeywordRemoveDataTable.delete([albumId, albumEntryId, keyword]).then(v => undefined),
                                this.albumEntryDataTable.get([albumId, albumEntryId])
                                    .then((storedEntry: AlbumEntryData | undefined) => {
                                        if (storedEntry !== undefined) {
                                            if (storedEntry.keywords !== undefined
                                                && storedEntry.keywords.find(k => k === keyword)) {
                                                return Promise.resolve();
                                            }
                                            return this.pendingKeywordAddDataTable.put({
                                                albumId,
                                                albumEntryId,
                                                keyword
                                            }).then(v => undefined);
                                        }
                                    }).then(v => undefined)]);
                    }
                }
            });
    }

    public async removeKeyword(albumId: string, albumEntryIds: string[], keywords: string[]): Promise<void> {
        await this.transaction('rw',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                for (const keyword of keywords) {
                    for (const albumEntryId of albumEntryIds) {
                        await Promise.all(
                            [
                                this.pendingKeywordAddDataTable.delete([albumId, albumEntryId, keyword]).then(v => undefined),
                                this.albumEntryDataTable.get([albumId, albumEntryId])
                                    .then((storedEntry: AlbumEntryData | undefined) => {
                                        if (storedEntry !== undefined
                                            && storedEntry.keywords !== undefined
                                            && storedEntry.keywords.find(k => k === keyword)) {
                                            return this.pendingKeywordRemoveDataTable.put({
                                                albumEntryId,
                                                albumId,
                                                keyword
                                            }).then(v => undefined);
                                        }
                                        return Promise.resolve();
                                    }).then(v => undefined)]);
                    }
                }
            });
    }

    public async currentKeywordStates(albumId: string, entries: Set<string>): Promise<Map<string, KeywordState>> {
        return this.transaction('r',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                const entryKeys: [string, string][] = [];
                entries.forEach(albumEntryId => entryKeys.push([albumId, albumEntryId]));
                const ret = new Map<string, KeywordState>();
                const entryFunction = entryId => {
                    const existingEntry = ret.get(entryId);
                    if (existingEntry !== undefined) {
                        return existingEntry;
                    }
                    const newEntry = {
                        existingKeywords: new Set<string>(),
                        pendingAddKeywords: new Set<string>(),
                        pendingRemoveKeywords: new Set<string>()
                    };
                    ret.set(entryId, newEntry);
                    return newEntry;
                };
                await Promise.all([
                    this.albumEntryByEntryAndAlbum
                        .anyOf(entryKeys)
                        .each(entry => {
                            const keywords = entryFunction(entry.albumEntryId).existingKeywords;
                            entry.keywords.forEach(kw => keywords.add(kw));
                        }),
                    this.pendingKeywordAddDataTable
                        .where(['albumId', 'albumEntryId'])
                        .anyOf(entryKeys)
                        .each(addEntry => entryFunction(addEntry.albumEntryId).pendingAddKeywords.add(addEntry.keyword)),
                    this.pendingKeywordRemoveDataTable
                        .where(['albumId', 'albumEntryId'])
                        .anyOf(entryKeys)
                        .each(removeEntry => entryFunction(removeEntry.albumEntryId).pendingRemoveKeywords.add(removeEntry.keyword))
                ]);
                return ret;
            });
    }

    public async pendingAddKeywords(albumId: string, addEntryCallback: (PendingKeywordAddEntry) => void) {
        await this.transaction('r', this.pendingKeywordAddDataTable, async () => {
            this.pendingKeywordAddDataTable.where('albumId').equals(albumId).each(addEntryCallback);
        });
    }

    public async pendingRemoveKeywords(albumId: string, removeEntryCallback: (PendingKeywordRemoveEntry) => void) {
        await this.transaction('r', this.pendingKeywordRemoveDataTable, async () => {
            this.pendingKeywordRemoveDataTable.where('albumId').equals(albumId).each(removeEntryCallback);
        });
    }

    public countPendingMutations(albumId: string): Promise<number> {
        return this.transaction('r', this.pendingKeywordAddDataTable, this.pendingKeywordRemoveDataTable, async () => {
            const [addCount, removeCount] = await Promise.all([
                this.pendingKeywordAddDataTable.where('albumId').equals(albumId).count(),
                this.pendingKeywordRemoveDataTable.where('albumId').equals(albumId).count()
            ]);
            return addCount + removeCount;
        });
    }

    public clearPendingMutations(albumId: string): Promise<void> {
        return this.transaction('rw', this.pendingKeywordAddDataTable, this.pendingKeywordRemoveDataTable, async () => {
            await Promise.all([
                this.pendingKeywordAddDataTable.where('albumId').equals(albumId).delete(),
                this.pendingKeywordRemoveDataTable.where('albumId').equals(albumId).delete()
            ]);
        });
    }

    public getAlbumEntry(albumId: string, albumEntryId: string): Promise<[AlbumEntryData, Set<string>] | undefined> {
        this.lastAccessTime.set(albumId, Date.now());
        return this.transaction('r',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                const key = [albumId, albumEntryId];
                const [entry, addKeywords, removeKeywords] = await Promise.all([
                    this.albumEntryDataTable.get(key),
                    this.whereAddPendingKeyword.equals(key).toArray(),
                    this.whereRemovePendingKeyword.equals(key).toArray()]);
                if (entry === undefined) {
                    return undefined;
                }
                const keywords = new Set(entry.keywords);
                addKeywords.forEach(e => keywords.add(e.keyword));
                removeKeywords.forEach(e => keywords.delete(e.keyword));
                return [entry, keywords];
            }
        );
    }

    public readImage(albumId: string, albumEntryId: string, minSize: number, online: boolean): Promise<ImageBlob | undefined> {
        return this.transaction('r', this.bigImagesTable, this.smallImagesTable, async () => {
            if (online) {
                if (minSize <= this.maxSmallScreenSize) {
                    const smallStoredData = await this.smallImagesTable.get([albumId, albumEntryId]);
                    if (smallStoredData !== undefined && smallStoredData.mediaSize >= minSize) {
                        return smallStoredData;
                    }
                    return undefined;
                }
                const bigStoredData = await this.bigImagesTable.get([albumId, albumEntryId]);
                if (bigStoredData !== undefined && bigStoredData.mediaSize >= minSize) {
                    return bigStoredData;
                }
                return undefined;
            } else {
                if (minSize <= this.maxSmallScreenSize) {
                    const smallStoredData = await this.smallImagesTable.get([albumId, albumEntryId]);
                    if (smallStoredData !== undefined && smallStoredData.mediaSize >= minSize) {
                        return smallStoredData;
                    }
                    const bigStoredData = await this.bigImagesTable.get([albumId, albumEntryId]);
                    if (bigStoredData !== undefined) {
                        return bigStoredData;
                    }
                    return smallStoredData;
                } else {
                    const bigStoredData = await this.bigImagesTable.get([albumId, albumEntryId]);
                    if (bigStoredData !== undefined) {
                        return bigStoredData;
                    }
                    return this.smallImagesTable.get([albumId, albumEntryId]);
                }
            }
        });
    }

    public findDeprecatedAlbums(): Promise<string[]> {
        return this.transaction('r', this.albumDataTable, this.albumSettingsTable, async () => {
            const ret: string[] = [];
            const albumVersions = new Map<string, string>();
            await this.albumDataTable.each(album => albumVersions.set(album.id, album.albumVersion));
            this.albumSettingsTable
                .filter(album => album.syncOffline && album.albumEntryVersion !== albumVersions.get(album.id))
                .each(album => ret.push(album.id));
            return ret;
        });
    }

    public findMissingImagesOfAlbum(albumId: string): Promise<[Set<string>, Set<string>]> {
        return this.transaction('r', this.albumEntryDataTable, this.bigImagesTable, this.smallImagesTable, async () => {
            const smallEntries = new Set<string>();
            const bigEntries = new Set<string>();
            await this.albumEntryDataTable.where('albumId').equals(albumId).each(entry => {
                bigEntries.add(entry.albumEntryId);
                smallEntries.add(entry.albumEntryId);
            });
            await this.smallImagesTable
                .where('albumId').equals(albumId).and(img => img.mediaSize >= this.maxSmallScreenSize)
                .each(img => smallEntries.delete(img.albumEntryId));
            await this.bigImagesTable
                .where('albumId').equals(albumId).and(img => img.mediaSize >= this.screenSize)
                .each(img => bigEntries.delete(img.albumEntryId));
            return [smallEntries, bigEntries];
        });
    }

    public async storeImages(data: ImageBlob[]): Promise<void> {
        await this.cleanupSemaphore.acquire();
        try {
            if (navigator.storage && navigator.storage.estimate) {
                const estimation = await navigator.storage.estimate();
                const quota = estimation.quota;
                const usage = estimation.usage;
                let totalSize = 0;
                data.forEach(img => totalSize += img.data.size);
                if (usage + totalSize * 1.5 > quota - 10 * 1000 * 1000) {
                    // console.log('storage limit reached');
                    // console.log(`Quota: ${quota}`);
                    // console.log(`Usage: ${usage}`);
                    await this.cleanupOldestAlbum();
                }
            }
        } finally {
            this.cleanupSemaphore.release();
        }
        return this.transaction('rw', this.smallImagesTable, this.bigImagesTable, async () => {
            for (const image of data) {
                if (image.mediaSize <= this.maxSmallScreenSize) {
                    const oldEntry = await this.smallImagesTable.get([image.albumId, image.albumEntryId]);
                    if (oldEntry === undefined || oldEntry.mediaSize < image.mediaSize) {
                        await this.smallImagesTable.put(image);
                    }
                } else {
                    const oldEntry = await this.bigImagesTable.get([image.albumId, image.albumEntryId]);
                    if (oldEntry === undefined || oldEntry.mediaSize < image.mediaSize) {
                        await this.bigImagesTable.put(image);
                    }

                }
            }
        });
    }

    public storeImage(data: ImageBlob): Promise<void> {
        return this.storeImages([data]);
    }


    private async cleanupOldestAlbum(): Promise<void> {
        return this.transaction('rw',
            this.albumSettingsTable,
            this.albumEntryDataTable,
            this.smallImagesTable,
            this.bigImagesTable, async () => {
                const albumsWithData: string[] = [];
                await this.albumSettingsTable.each(a => {
                    if (a.albumEntryVersion !== undefined) {
                        albumsWithData.push(a.id);
                    }
                });
                // cleanup orphaned entries
                const deleteOrphanCount = await this.albumEntryDataTable.where('albumId').noneOf(albumsWithData).delete() +
                    await this.bigImagesTable.where('albumId').noneOf(albumsWithData).delete() +
                    await this.smallImagesTable.where('albumId').noneOf(albumsWithData).delete();
                if (deleteOrphanCount > 0) {
                    return;
                }
                let oldestEntryTime = Number.MAX_SAFE_INTEGER;
                let oldestEntryKey;
                albumsWithData.forEach(key => {
                    const time = this.lastAccessTime.get(key) || -1;
                    if (time < oldestEntryTime) {
                        oldestEntryKey = key;
                        oldestEntryTime = time;
                    }
                });
                const nextFoundKey = oldestEntryKey;
                if (nextFoundKey === undefined) {
                    return;
                }
                const albumSettings = await this.albumSettingsTable.get(nextFoundKey);
                // remove images
                if (albumSettings !== undefined) {
                    albumSettings.syncOffline = false;
                    albumSettings.offlineSyncedVersion = undefined;
                    await this.albumSettingsTable.put(albumSettings);
                }
                const removedImages = await this.bigImagesTable.where('albumId').equals(nextFoundKey).delete() +
                    await this.bigImagesTable.where('albumId').equals(nextFoundKey).delete()
                ;
                if (removedImages > 0) {
                    return;
                }
                // remove entries
                if (albumSettings !== undefined) {
                    albumSettings.albumEntryVersion = undefined;
                    await this.albumSettingsTable.put(albumSettings);
                }
                await this.albumEntryDataTable.where('albumId').equals(nextFoundKey).delete();
            }).catch(ex => {
            console.error('cannot cleanup data', ex);
        });
    }

    public getUserPermissions(): Promise<UserPermissions | undefined> {
        return this.transaction('r',
            this.userPermissionsTable, async () => {
                const entries = await this.userPermissionsTable.toArray();
                if (entries.length > 0) {
                    return entries[0];
                } else {
                    return undefined;
                }
            });
    }

    public setUserPermissions(userPermissions: UserPermissions): Promise<void> {
        return this.transaction('rw', this.userPermissionsTable, async () => {
            await this.userPermissionsTable.clear();
            await this.userPermissionsTable.put(userPermissions);
        });
    }

    public getDeviceData(): Promise<DeviceData | undefined> {
        return this.transaction('r', this.deviceDataTable, async () => {
            const entries = await this.deviceDataTable.toArray();
            if (entries.length > 0) {
                return entries[0];
            }
            return undefined;
        });
    }

    public setDeviceData(deviceData: DeviceData): Promise<void> {
        return this.transaction('rw', this.deviceDataTable, async () => {
            await this.deviceDataTable.clear();
            await this.deviceDataTable.put(deviceData);
            this.screenSize = deviceData.screenSize;
            this.maxSmallScreenSize = this.screenSize / 4;
        });
    }

    public findAlbumsToSync(): Promise<AlbumSettings[]> {
        return this.transaction('r',
            this.albumSettingsTable,
            this.albumDataTable,
            async () => {
                const versions = new Map<string, string>();
                await this.albumDataTable.each(album => versions.set(album.id, album.albumVersion));
                return this.albumSettingsTable
                    .filter(album => album.syncOffline
                        && versions.get(album.id) === album.albumEntryVersion
                        && album.albumEntryVersion !== album.offlineSyncedVersion)
                    .toArray();
            });
    }

    public setSync(albumId: string, enabled: boolean): Promise<void> {
        return this.transaction('rw', this.albumSettingsTable, async () => {
            const albumSettings = await this.albumSettingsTable.get(albumId);
            if (albumSettings === undefined) {
                this.albumSettingsTable.put({
                    albumEntryVersion: undefined,
                    lastUpdated: 0,
                    offlineSyncedVersion: undefined,
                    syncOffline: enabled,
                    id: albumId
                });
            } else if (albumSettings.syncOffline !== enabled) {
                albumSettings.syncOffline = enabled;
                albumSettings.offlineSyncedVersion = undefined;
                await this.albumSettingsTable.put(albumSettings);
            }
        });
    }

    public setOfflineSynced(id: string, albumEntryVersion: string): Promise<void> {
        return this.transaction('rw', this.albumSettingsTable, async () => {
            const alumData = await this.albumSettingsTable.get(id);
            if (alumData === undefined) {
                return;
            }
            alumData.offlineSyncedVersion = albumEntryVersion;
            await this.albumSettingsTable.put(alumData);
        });
    }

    public listOfflineAvailableVersions(): Promise<Map<string, string>> {
        return this.transaction('r', this.albumSettingsTable, async () => {
            const ret = new Map<string, string>();
            await this.albumSettingsTable
                .where('offlineSyncedVersion')
                .notEqual(undefined).each(settings => ret.set(settings.id, settings.offlineSyncedVersion));
            return ret;
        });
    }

    public getAlbumAndSettings(albumId: string): Promise<[AlbumData | undefined, AlbumSettings | undefined]> {
        return this.transaction('r', this.albumDataTable, this.albumSettingsTable, () => {
            return Promise.all([this.albumDataTable.get(albumId),
                this.albumSettingsTable.get(albumId)]);
        });

    }
}

function isQuotaExceeded(ex: any) {
    return (ex.name === 'QuotaExceededError') ||
        (ex.inner && ex.inner.name === 'QuotaExceededError');

}
