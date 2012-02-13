package zo.co.quirklabs.mongofile

import com.mongodb.gridfs.GridFS
import eu.medsea.mimeutil.detector.MagicMimeMimeDetector
import org.springframework.web.multipart.commons.CommonsMultipartFile
import com.mongodb.gridfs.GridFSInputFile
import com.mongodb.DBObject
import com.mongodb.gridfs.GridFSDBFile
import org.bson.types.ObjectId
import com.mongodb.WriteConcern
import javax.servlet.http.HttpServletResponse
import org.apache.commons.io.IOUtils
import org.codehaus.groovy.grails.commons.ApplicationHolder
import com.mongodb.DB
import com.mongodb.BasicDBObject
import com.mongodb.gridfs.GridFSFile

class FileService {

	static transactional = false

	def mongo
	private HashMap<String, GridFS> _gridfs = new HashMap<String, GridFS>()

	/**
	 * Save a multipart file linked to an external id. Deletes any file currently linked to that id.
	 *
	 * @param bucket
	 * @param file
	 * @param filename
	 * @param metaData
	 * @return GridFSFile new file
	 */
	public GridFSFile saveFile(CommonsMultipartFile file, Class domainClass, Long id, String fieldName = '') {
	    String bucket = getBucket(domainClass, fieldName)
	    
        deleteFile(domainClass, id)
        saveFile(bucket, file, null, [id: id])
    }
    
	/**
	 * Save a multipart file with custom metadata
	 *
	 * @param bucket
	 * @param file
	 * @param filename
	 * @param metaData
	 * @return GridFSFile new file
	 */
	public GridFSFile saveFile(String bucket, CommonsMultipartFile file, String filename = null, Map metaData = null) {
		GridFS gfs = getGridfs(bucket);

		// get content type
		byte[] b = new byte[4096]
		BufferedInputStream s = new BufferedInputStream(file.getInputStream(), 6144)
		s.mark(0)
		s.read(b, 0, 4096)
		s.reset()
		def contentType = getMimeType(b)

		GridFSInputFile gInputFile = gfs.createFile(s);
		if (!filename) filename = file.getOriginalFilename()
		gInputFile.setFilename(filename);
		gInputFile.setContentType(contentType);
		if (metaData) {
			DBObject mD = new BasicDBObject()
			metaData.each { mD.put(it.key.toString(), it.value) }
			gInputFile.setMetaData(mD)
		}

		_saveFile(gfs, bucket, gInputFile)
	}

	public GridFSFile saveFile(String bucket, File file, String filename, Map metaData = null) {
		GridFS gfs = getGridfs(bucket);

		GridFSInputFile gInputFile = gfs.createFile(file.newInputStream());
		gInputFile.setFilename(filename);
		gInputFile.setContentType(getMimeType(file));
		if (metaData) {
			DBObject mD = new BasicDBObject()
			metaData.each { mD.put(it.key.toString(), it.value) }
			gInputFile.setMetaData(mD)
		}

		_saveFile(gfs, bucket, gInputFile)
	}

	/**
	 * e.g. for images, where you have bytedata only
	 * @param bucket
	 * @param fileContents
	 * @param filename
	 * @param metaData
	 * @return
	 */
	public GridFSFile saveFile(String bucket, byte[] fileContents, String filename, Map metaData = null) {
		GridFS gfs = getGridfs(bucket);

		GridFSInputFile gInputFile = gfs.createFile(new ByteArrayInputStream(fileContents));
		gInputFile.setFilename(filename);
		gInputFile.setContentType(getMimeType(fileContents));
		if (metaData) {
			DBObject mD = new BasicDBObject()
			metaData.each { mD.put(it.key.toString(), it.value) }
			gInputFile.setMetaData(mD)
		}

		_saveFile(gfs, bucket, gInputFile)
	}

	private GridFSFile _saveFile(GridFS gfs, String bucket, GridFSInputFile gInputFile) {
		try {
			gInputFile.save()
		} catch(Exception e) {
			log.error('could not save file ' + gInputFile + ': ' + e.message)
		}

		return gInputFile
	}

	def GridFSDBFile getFile(String bucket, String id, boolean asObjectId = true) {
		GridFS gfs = getGridfs(bucket);

		if (asObjectId) {
			return gfs.findOne(new ObjectId(id))
		} else {
			return gfs.findOne(new BasicDBObject('_id', id))
		}
	}

    def GridFSDBFile getFile(Class domainClass, Long id, String fieldName = '') {
        String bucket = getBucket(domainClass, fieldName)

		return findFile(bucket, ['metadata.id': id])
	}

	def GridFSDBFile findFile(String bucket, Map query) {
		GridFS gfs = getGridfs(bucket);

		return gfs.findOne(new BasicDBObject(query))
	}

    def deleteFile(Class domainClass, Long id, String fieldName = '') {
        String bucket = getBucket(domainClass, fieldName)
        
        deleteFile(bucket, ['metadata.id': id])
    }
    
	def deleteFile(String bucket, Map query) {
		GridFS gfs = getGridfs(bucket);

		gfs.remove(query as BasicDBObject)
	}
	
	def dropDatabase() {
	    def dbname = ApplicationHolder.application.config.mongodb?.database
		dbname = dbname?dbname+'db':'db' // use db '<DBNAME>files' for files
		DB db = mongo.mongo.getDB(dbname)
		db.dropDatabase()
	}

	private GridFS getGridfs(String bucket) {
		def gridfs = _gridfs[bucket]
		if (!gridfs) {
			def dbname = ApplicationHolder.application.config.mongodb?.database
			dbname = dbname?dbname+'db':'db' // use db '<DBNAME>files' for files
			DB db = mongo.mongo.getDB(dbname)

			db.setWriteConcern(WriteConcern.SAFE)
			gridfs = new GridFS(db, bucket)
			_gridfs[bucket] = gridfs

			// set indices
			db.getCollection(bucket + ".files").ensureIndex(new BasicDBObject("metadata.id", 1), new BasicDBObject('unique', true).append('dropDups', true));
		}

		gridfs
	}
	
	private String getBucket(Class clazz, String fieldName) {
	    String bucket = clazz.simpleName.toLowerCase()
	    if(fieldName) {
	        bucket = "${bucket}_${fieldName}"
	    }
	    
	    bucket
	}

	public String getMimeType(File file) {
		// use mime magic
		MagicMimeMimeDetector detector = new MagicMimeMimeDetector();
		Collection mimeTypes = detector.getMimeTypesFile(file);
		if (mimeTypes) return mimeTypes[0].toString()

		return "application/octet-stream"
	}

	public String getMimeType(byte[] ba) {
		// use mime magic
		MagicMimeMimeDetector detector = new MagicMimeMimeDetector();
		Collection mimeTypes = detector.getMimeTypesByteArray(ba);
		if (mimeTypes) return mimeTypes.iterator().getAt(0).toString()

		return "application/octet-stream"
	}
	
	public void deliverFile(HttpServletResponse response, boolean asAttachment, Class domainClass, Long id, String fieldName = '') {
        GridFSDBFile file = getFile(domainClass, id, fieldName)
        if(file == null) {
            throw new FileNotFoundException("Could not find ${domainClass.name} file for id ${id}")
        }

        deliverGridFSFile(response, file, null, asAttachment)
    }

	/**
	 * sends the file to the client
	 * if no filename is given, the one from the gridfsfile is used
	 *
	 * @param response
	 * @param file
	 * @param filename
	 */
	public void deliverGridFSFile(HttpServletResponse response, GridFSDBFile file, String filename = null, boolean asAttachment = true) {
		response.setContentType file.getContentType()
		response.setContentLength ((int)file.getLength())
		if (filename == null) filename = file.getFilename()
		if (asAttachment) response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
		// response.setHeader("Cache-Control", "no-store");
		// response.setHeader("Pragma", "no-cache");
		// response.setDateHeader("Expires", 0);

		try {
			IOUtils.copy(file.getInputStream(), response.getOutputStream())
		} catch (Exception e) {
			try {
				// todo log
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
			} catch (IOException ignored) {}
		}
	}

	/**
	 * send local file to client
	 * if mimetype = null, it is guessed by file content (mime magic)
	 * if filename = null, the original file name is used
	 *
	 * @param response
	 * @param file
	 * @param filename
	 * @param mimeType
	 */
	public void deliverLocalFile(HttpServletResponse response, File file, String filename = null, String mimeType = null, boolean asAttachment = true) {
		if (!file.exists()) {
			try {
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
			} catch (Exception e2) {}
			return;
		}

		// handle length
		long size = file.length();
		if (size>0) response.setContentLength((int)size);

		if (filename == null) filename = file.getName()
		if (asAttachment) response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
		// response.setHeader("Cache-Control", "no-store");
		// response.setHeader("Pragma", "no-cache");
		// response.setDateHeader("Expires", 0);

		// handle mimetype - if mimetype not known, then send code forbidden
		if (mimeType == null) mimeType = getMimeType(file)
		response.setContentType(mimeType);

		// send content
		try {
			IOUtils.copy(new FileInputStream(file), response.getOutputStream())
		} catch (Exception e) {
			try {
				// @todo log
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
			} catch (IOException ignored) {}
		}
	}
}