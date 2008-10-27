/*
 *  soapUI, copyright (C) 2004-2008 eviware.com 
 *
 *  soapUI is free software; you can redistribute it and/or modify it under the 
 *  terms of version 2.1 of the GNU Lesser General Public License as published by 
 *  the Free Software Foundation.
 *
 *  soapUI is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without 
 *  even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
 *  See the GNU Lesser General Public License for more details at gnu.org.
 */

package com.eviware.soapui.impl.support.definition.support;

import com.eviware.soapui.SoapUI;
import com.eviware.soapui.impl.support.AbstractInterface;
import com.eviware.soapui.impl.support.DefinitionContext;
import com.eviware.soapui.impl.support.definition.DefinitionCache;
import com.eviware.soapui.impl.support.definition.DefinitionLoader;
import com.eviware.soapui.impl.support.definition.InterfaceDefinition;
import com.eviware.soapui.impl.support.definition.InterfaceDefinitionPart;
import com.eviware.soapui.impl.wsdl.support.PathUtils;
import com.eviware.soapui.impl.wsdl.support.xsd.SchemaException;
import com.eviware.soapui.support.UISupport;
import com.eviware.x.dialogs.Worker;
import com.eviware.x.dialogs.XProgressDialog;
import com.eviware.x.dialogs.XProgressMonitor;
import org.apache.log4j.Logger;
import org.apache.xmlbeans.SchemaTypeLoader;
import org.apache.xmlbeans.SchemaTypeSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holder for InterfaceDefinitions and related SchemaTypeLoader types
 *
 * @author Ole.Matzura
 */

public abstract class AbstractDefinitionContext<T extends AbstractInterface, T2 extends DefinitionLoader, T3 extends AbstractInterfaceDefinition<T>> implements DefinitionContext
{
   private String url;
   private T3 definition;
   private boolean loaded;
   private SchemaException schemaException;

   private final static Logger log = Logger.getLogger( AbstractDefinitionContext.class );

   private T2 currentLoader;
   private T iface;

   private static Map<String, InterfaceDefinition> definitionCache = new HashMap<String, InterfaceDefinition>();
   private static Map<String, Integer> urlReferences = new HashMap<String, Integer>();

   public AbstractDefinitionContext( String url, T iface )
   {
      this.url = PathUtils.ensureFilePathIsUrl( url );
      this.iface = iface;
   }

   public AbstractDefinitionContext( String url )
   {
      this( url, null );
   }

   public T getInterface()
   {
      return iface;
   }

   public T3 getInterfaceDefinition() throws Exception
   {
      loadIfNecessary();
      return (T3) ( definition == null ? definitionCache.get( url ) : definition );
   }

   public boolean isLoaded()
   {
      return loaded;
   }

   public synchronized boolean loadIfNecessary() throws Exception
   {
      if( !loaded )
         load();
      return loaded;
   }

   public synchronized void setDefinition( String url, boolean updateCache ) throws Exception
   {
      if( !url.equals( this.url ) )
      {
         this.url = url;

         if( updateCache )
         {
            definitionCache.remove( url );
            loaded = false;
            load();
         }

         loaded = iface != null && definitionCache.containsKey( url );
      }
   }

   public synchronized boolean load() throws Exception
   {
      return load( null );
   }

   public synchronized boolean load( T2 wsdlLoader ) throws Exception
   {
      if( !loaded && iface != null )
      {
         loaded = definitionCache.containsKey( url );
      }

      if( loaded )
         return true;

      // always use progressDialog since files can import http urls
      XProgressDialog progressDialog = UISupport.getDialogs().createProgressDialog(
              "Loading Definition", 3, "Loading definition..", true );

      Loader loader = new Loader( wsdlLoader );
      progressDialog.run( loader );

      // Get the value. It is the responsibility of the progressDialog to
      // wait for the other thread to finish.
      if( loader.hasError() )
      {
         if( loader.getError() instanceof SchemaException )
         {
            schemaException = (SchemaException) loader.getError();
            ArrayList<?> errorList = schemaException.getErrorList();

            log.error( "Error loading schema types from " + url + ", see log for details" );

            if( errorList != null )
            {
               for( int c = 0; c < errorList.size(); c++ )
               {
                  log.error( errorList.get( c ).toString() );
               }
            }
         }
         else throw new Exception( loader.getError() );
      }
      else loaded = true;

      return loaded;
   }

   public SchemaTypeLoader getSchemaTypeLoader() throws Exception
   {
      loadIfNecessary();
      return iface != null ? definitionCache.get( url ).getSchemaTypeLoader() :
              definition != null ? definition.getSchemaTypeLoader() : null;
   }

   public SchemaException getSchemaException()
   {
      return schemaException;
   }

   private class Loader extends Worker.WorkerAdapter
   {
      private Throwable error;
      private T2 wsdlLoader;

      public Loader( T2 wsdlLoader )
      {
         super();
         this.wsdlLoader = wsdlLoader;
      }

      private T2 getDefinitionLoader()
      {
         if( wsdlLoader != null )
         {
            return wsdlLoader;
         }
         else
         {
            return createDefinitionLoader( url );
         }
      }

      public boolean hasError()
      {
         return error != null;
      }

      public Object construct( XProgressMonitor monitor )
      {
         try
         {
            DefinitionCache cache = iface == null ?
                    new StandaloneDefinitionCache<T>() :
                    new InterfaceConfigDefinitionCache<T>( iface );

            if( !cache.validate() )
            {
               monitor.setProgress( 1, "Caching Definition from url [" + url + "]" );

               currentLoader = getDefinitionLoader();
               currentLoader.setProgressMonitor( monitor, 2 );

               cache.update( currentLoader );

               if( currentLoader.isAborted() )
                  throw new Exception( "Loading of Definition from [" + url + "] was aborted" );
            }

            monitor.setProgress( 1, "Loading Definition from " + ( iface == null ? "url" : "cache" ) );

            log.debug( "Loading Definition..." );
            cacheDefinition( cache );
            return null;
         }
         catch( Throwable e )
         {
            log.error( "Loading of definition failed for [" + url + "]; " + e );
            SoapUI.logError( e );
            this.error = e;
            return e;
         }
         finally
         {
            currentLoader = null;
         }
      }

      public Throwable getError()
      {
         return error;
      }

      public boolean onCancel()
      {
         if( currentLoader == null )
            return false;

         return currentLoader.abort();
      }
   }

   private void cacheDefinition( DefinitionCache cache ) throws Exception
   {
      currentLoader = createDefinitionLoader( cache );
      currentLoader.setProgressInfo( "Loading Definition" );
      definition = loadDefinition( currentLoader );
      definition.setDefinitionCache( cache );

      log.debug( "Loaded Definition: " + ( definition != null ? "ok" : "null" ) );

      if( !currentLoader.isAborted() && iface != null && iface.isDefinitionShareble() )
      {
         definitionCache.put( url, definition );
         if( urlReferences.containsKey( url ) )
         {
            urlReferences.put( url, urlReferences.get( url ) + 1 );
         }
         else
         {
            urlReferences.put( url, 1 );
         }
      }

      if( currentLoader.isAborted() )
         throw new Exception( "Loading of Definition from [" + url + "] was aborted" );

      loaded = true;
   }

   protected abstract T2 createDefinitionLoader( DefinitionCache definitionCache );

   protected abstract T2 createDefinitionLoader( String url );

   protected abstract T3 loadDefinition( T2 loader ) throws Exception;

   public void release()
   {
      if( iface != null && urlReferences.containsKey( url ) )
      {
         Integer i = urlReferences.get( url );
         if( i.intValue() <= 1 )
         {
            urlReferences.remove( url );
            definitionCache.remove( url );
         }
         else
         {
            urlReferences.put( url, i - 1 );
         }
      }
   }

   public SchemaTypeSystem getSchemaTypeSystem() throws Exception
   {
      if( !loaded )
         load();

      if( !definitionCache.containsKey( url ) )
         return null;

      return definitionCache.get( url ).getSchemaTypeSystem();
   }

   public boolean hasSchemaTypes()
   {
      try
      {
         loadIfNecessary();
      }
      catch( Exception e )
      {
         SoapUI.logError( e );
         return false;
      }
      return definitionCache.containsKey( url ) && definitionCache.get( url ).hasSchemaTypes();
   }

   public String getUrl()
   {
      return url;
   }

   public void setInterface( T iface )
   {
      if( this.iface == null && iface != null )
      {
         if( definition != null )
         {
            if( definition.getDefinitionCache().validate() )
            {
               InterfaceConfigDefinitionCache cache = new InterfaceConfigDefinitionCache( iface );
               try
               {
                  cache.importCache( definition.getDefinitionCache() );
               }
               catch( Exception e )
               {
                  SoapUI.logError( e );
               }

               definition.setDefinitionCache( cache );
            }

            definition.setIface( iface );
            definitionCache.put( url, definition );
         }
         else
         {
            loaded = false;
         }
      }

      this.iface = iface;
   }

   public static void uncache( String url )
   {
      definitionCache.remove( url );
      urlReferences.remove( url );
   }

   public void reload() throws Exception
   {
      getDefinitionCache().clear();
      loaded = false;
      load();
   }

   public boolean isCached()
   {
      return isLoaded() && definition != null && definition.getDefinitionCache() != null;
   }

   public List<InterfaceDefinitionPart> getDefinitionParts() throws Exception
   {
      loadIfNecessary();

      return getInterfaceDefinition().getDefinitionParts();
   }

   public DefinitionCache getDefinitionCache() throws Exception
   {
      loadIfNecessary();

      return getInterfaceDefinition().getDefinitionCache();
   }
}