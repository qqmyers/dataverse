/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.harvard.iq.dataverse;

import com.amazonaws.AmazonClientException;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import edu.harvard.iq.dataverse.api.AbstractApiBean;
import edu.harvard.iq.dataverse.batch.jobs.importer.filesystem.FileRecordWriter;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.exception.IllegalCommandException;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.util.FileUtil;
import static edu.harvard.iq.dataverse.util.json.NullSafeJsonBuilder.jsonObjectBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.inject.Named;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import org.apache.commons.io.IOUtils;

/**
 * This class is for importing files added to s3 outside of dataverse.
 * Specifically, it is intended to be used along dcm.
 * Most of this code has been ported from FileRecordWriter, pruning out
 * the incomplete sections for importing individual files instead of folder-packages
 * @author matthew
 */

@Named
@Stateless
public class S3PackageImporter extends AbstractApiBean implements java.io.Serializable{
    
    private static final Logger logger = Logger.getLogger(S3PackageImporter.class.getName());

    private AmazonS3 s3 = null;
    
    @EJB
    DataFileServiceBean dataFileServiceBean;

    @EJB
    EjbDataverseEngine commandEngine;
    
    public void copyFromS3(Dataset dataset, String s3ImportPath) throws IOException {
        try {
            s3 = AmazonS3ClientBuilder.standard().defaultClient();
        } catch (Exception e) {
            throw new AmazonClientException(
                    "Cannot instantiate a S3 client using; check your AWS credentials and region",
                    e);
        }

        JsonObjectBuilder bld = jsonObjectBuilder();

        String fileMode = FileRecordWriter.FILE_MODE_PACKAGE_FILE;

        String dcmBucketName = System.getProperty("dataverse.files.dcm-s3-bucket-name");
        String dcmDatasetKey = s3ImportPath;
        String dvBucketName = System.getProperty("dataverse.files.s3-bucket-name");

        String dvDatasetKey = getS3DatasetKey(dataset);
        
        logger.log(Level.INFO, "S3 Import related attributes. dcmBucketName: {0} | dcmDatasetKey: {1} | dvBucketName: {2} | dvDatasetKey: {3} |", 
                new Object[]{dcmBucketName, dcmDatasetKey, dvBucketName, dvDatasetKey});
        
        if (dataset.getVersions().size() != 1) {
            String error = "Error creating FilesystemImportJob with dataset with ID: " + dataset.getId() + " - Dataset has more than one version.";
            logger.info(error);
            throw new IllegalStateException(error);
        }

        if (dataset.getLatestVersion().getVersionState() != DatasetVersion.VersionState.DRAFT) {
            String error = "Error creating FilesystemImportJob with dataset with ID: " + dataset.getId() + " - Dataset isn't in DRAFT mode.";
            logger.info(error);
            throw new IllegalStateException(error);
        }

        ListObjectsRequest req = new ListObjectsRequest().withBucketName(dcmBucketName).withPrefix(dcmDatasetKey);
        ObjectListing storedDcmDatsetFilesList;
        try {
            storedDcmDatsetFilesList = s3.listObjects(req);
        } catch (SdkClientException sce) {
            logger.info("Caught an SdkClientException in s3ImportUtil:    " + sce.getMessage());
            throw new IOException ("S3 listAuxObjects: failed to get a listing for "+dcmDatasetKey);
        }
        List<S3ObjectSummary> storedDcmDatasetFilesSummary = storedDcmDatsetFilesList.getObjectSummaries();
        try {
            while (storedDcmDatsetFilesList.isTruncated()) {
                logger.fine("S3 listAuxObjects: going to next page of list");
                storedDcmDatsetFilesList = s3.listNextBatchOfObjects(storedDcmDatsetFilesList);
                if (storedDcmDatsetFilesList != null) {
                    storedDcmDatasetFilesSummary.addAll(storedDcmDatsetFilesList.getObjectSummaries());
                }
            }
        } catch (AmazonClientException ase) {
            logger.info("Caught an AmazonServiceException in s3ImportUtil:    " + ase.getMessage());
            throw new IOException("S3AccessIO: Failed to get aux objects for listing.");
        }
        for (S3ObjectSummary item : storedDcmDatasetFilesSummary) {

            logger.log(Level.INFO, "S3 Import file copy for {0}", new Object[]{item});
            String dcmFileKey = item.getKey();

            String copyFileName = dcmFileKey.substring(dcmFileKey.lastIndexOf('/') + 1);

            logger.log(Level.INFO, "S3 file copy related attributes. dcmBucketName: {0} | dcmFileKey: {1} | dvBucketName: {2} | copyFilePath: {3} |", 
                new Object[]{dcmBucketName, dcmFileKey, dvBucketName, dvDatasetKey+"/"+copyFileName});

            s3.copyObject(new CopyObjectRequest(dcmBucketName, dcmFileKey, dvBucketName, dvDatasetKey+"/"+copyFileName));                
            
            try {
                s3.deleteObject(new DeleteObjectRequest(dcmBucketName, dcmFileKey));
            }  catch (AmazonClientException ase) {
                logger.warning("Caught an AmazonClientException deleting s3 object from dcm bucket: " + ase.getMessage());
                throw new IOException("Failed to delete object" + new Object[]{item});
            }
        }

    }
    
    public DataFile createPackageDataFile(Dataset dataset, String folderName, long totalSize) {
            DataFile packageFile = new DataFile(DataFileServiceBean.MIME_TYPE_PACKAGE_FILE);
            packageFile.setChecksumType(DataFile.ChecksumType.SHA1);
            
            FileUtil.generateStorageIdentifier(packageFile);
            
            String dvBucketName = System.getProperty("dataverse.files.s3-bucket-name");
            String dvDatasetKey = getS3DatasetKey(dataset);

            //This is a brittle calculation, changes of the dcm post_upload script will blow this up
            String rootPackageName = "package_" + folderName.replace("/", "");
            
            //getting the name of the .sha file via substring, ${packageName}.sha
            logger.log(Level.INFO, "shaname {0}", new Object[]{rootPackageName  + ".sha"});

            S3Object s3FilesSha = s3.getObject(new GetObjectRequest(dvBucketName, dvDatasetKey + "/" + rootPackageName  + ".sha"));
           

            InputStreamReader str = new InputStreamReader(s3FilesSha.getObjectContent());
            BufferedReader reader = new BufferedReader(str);
            String checksumVal = "";
            try {
                String line;
                while((line = reader.readLine()) != null && checksumVal.isEmpty()) {
                    logger.log(Level.INFO, "line {0}", new Object[]{line});
                    String[] splitLine = line.split("  ");
                    //logger.log(Level.INFO, "root package name {0}", new Object[]{rootPackageName + ".zip"});
                    //logger.log(Level.INFO, "splitline {0} | {1}", new Object[]{splitLine[0], splitLine[1]});
                    
                    //the sha file should only contain one line, but incase it doesn't we will check for the one for our zip
                    if(splitLine[1].contains(rootPackageName + ".zip")) { 
                        checksumVal = splitLine[0];
                        logger.log(Level.INFO, "checksumVal found {0}", new Object[]{checksumVal});
                    }
                }
                if(checksumVal.isEmpty()) {
                    logger.log(Level.SEVERE, "No checksum found for uploaded DCM S3 zip on dataset {0}", new Object[]{dataset.getIdentifier()});
                }                
            } catch (IOException ex){
                logger.log(Level.SEVERE, "Error parsing DCM s3 checksum file on dataset {0} . Error: {1} ", new Object[]{dataset.getIdentifier(), ex});
            } finally {
                try {
                    str.close();
                    reader.close();
                } catch (IOException ex) {
                    logger.log(Level.WARNING, "errors closing s3 DCM object reader stream: {0}", new Object[]{ex});
                }
                
            }
            
            //MAD: CLOSE THINGS
            
            //String shaFileContents = IOUtils.toString(s3FilesSha.getObjectContent(), StandardCharsets.UTF_8);

            //We parse the checksum from the passed sha 1
            //String checksumVal = shaFileContents.

            logger.log(Level.INFO, "Checksum value for the package in Dataset {0} is: {1}", 
               new Object[]{dataset.getIdentifier(), checksumVal});

            packageFile.setChecksumValue(checksumVal); 

            packageFile.setFilesize(totalSize);
            packageFile.setModificationTime(new Timestamp(new Date().getTime()));
            packageFile.setCreateDate(new Timestamp(new Date().getTime()));
            packageFile.setPermissionModificationTime(new Timestamp(new Date().getTime()));
            packageFile.setOwner(dataset);
            dataset.getFiles().add(packageFile);

            packageFile.setIngestDone();

            // set metadata and add to latest version
            FileMetadata fmd = new FileMetadata();
            fmd.setLabel(rootPackageName + ".zip");
            
            fmd.setDataFile(packageFile);
            packageFile.getFileMetadatas().add(fmd);
            if (dataset.getLatestVersion().getFileMetadatas() == null) dataset.getLatestVersion().setFileMetadatas(new ArrayList<>());

            dataset.getLatestVersion().getFileMetadatas().add(fmd);
            fmd.setDatasetVersion(dataset.getLatestVersion());
            
            GlobalIdServiceBean idServiceBean = GlobalIdServiceBean.getBean(packageFile.getProtocol(), commandEngine.getContext());
            if (packageFile.getIdentifier() == null || packageFile.getIdentifier().isEmpty()) {
                String packageIdentifier = dataFileServiceBean.generateDataFileIdentifier(packageFile, idServiceBean);
                packageFile.setIdentifier(packageIdentifier);
            }
            
            String nonNullDefaultIfKeyNotFound = "";
            String protocol = commandEngine.getContext().settings().getValueForKey(SettingsServiceBean.Key.Protocol, nonNullDefaultIfKeyNotFound);
            String authority = commandEngine.getContext().settings().getValueForKey(SettingsServiceBean.Key.Authority, nonNullDefaultIfKeyNotFound);

            if (packageFile.getProtocol() == null) {
                packageFile.setProtocol(protocol);
            }
            if (packageFile.getAuthority() == null) {
                packageFile.setAuthority(authority);
            }

            if (!packageFile.isIdentifierRegistered()) {
                String doiRetString = "";
                idServiceBean = GlobalIdServiceBean.getBean(commandEngine.getContext());
                try {
                    doiRetString = idServiceBean.createIdentifier(packageFile);
                } catch (Throwable e) {
                    
                }

                // Check return value to make sure registration succeeded
                if (!idServiceBean.registerWhenPublished() && doiRetString.contains(packageFile.getIdentifier())) {
                    packageFile.setIdentifierRegistered(true);
                    packageFile.setGlobalIdCreateTime(new Date());
                }
            }

        return packageFile;
    }
    
    public String getS3DatasetKey(Dataset dataset) {
        return dataset.getAuthority() + "/" + dataset.getIdentifier();
    }
}