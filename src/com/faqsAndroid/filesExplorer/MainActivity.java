package com.faqsAndroid.filesExplorer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

// La clase hereda las funciones de la superclase Activity
// e implementa el método OnItemClickListener
// que permite a la actividad captar los clicks en la lista
// de archivos (ListView)
// Además implementamos el evento OnClickListener, que nos
// permitirá capturar los clicks en botones...

public class MainActivity extends Activity implements OnItemClickListener
{	
	// Definimos un nombre para los mensajes de error o info.
	
	private static final String TAG = "faqsAndroid.filesExplorer";
	
	// Directorio raíz por defecto.
	
	private static final String ROOT_DIRECTORY = "/mnt/sdcard";		
	
	// Otras constantes.
	
	private static final String ITEM_KEY = "key";
	private static final String ITEM_IMAGE = "image";

	// Clase que permite comparar los nombres de los archivos, 
	// para presentarlos de forma ordenada en la lista.
	// Los directorios se muestran primero.
	
	private class FileNamesComparatorFoldersUp implements Comparator<File>
	{
		// Al comparar tenemos en cuenta si uno de los ficheros
		// es un directorio, ya que los posicionaremos primero.
		
		public int compare(File left, File right) 
		{
			if (left.isDirectory())
			{
				if (right.isDirectory())
				{
					return left.compareTo(right);
				}
				return -1;
			}
			return right.isDirectory() ? 1 : left.compareTo(right);
		}
	}
	
	// Otro modo de ordenar.
	// Por orden alfabético, pero sin mostrar primero los directorios...
	
	private class FileNamesComparatorFoldersNotUp implements Comparator<File>
	{
		public int compare(File left, File right) 
		{
			return left.compareTo(right);
		}
	} 

	// Flujo que obtiene los archivos del directorio
	// y cuando finaliza envía un mensaje al flujo principal,
	// que es el que visualizará el resultado...
	
	private class GetFilesThread extends Thread
	{		
		public void run()
		{
			// Ejecutamos la antigua función que obtenía los archivos (eso no cambia)...
			
			int result = _showDirectoryContents();
			
			// Le pasamos al flujo principal el parámetro
			
			Message message = new Message();
			message.what = 1; // El código de operación es el "1"...
			message.arg1 = result;
			
			iHandler.sendMessage(message);
		}
	}
	
	// Implementación del Handler. 
	// En este caso lo único que hace es forzar un repintado de la lista...
	
	private class MainActivityHandler extends Handler
	{
		public void handleMessage(Message message)
		{
			// Si recibimos un "1" indica que la operación que ha acabado es
			// la de obtener los ficheros del directorio...
			
			if (message.what == 1)
			{
				// Si recibimos un "0" es que el flujo secundario ha acabado
				// y visualizamos el nuevo directorio...
			
				if (message.arg1 == 0) 
				{
					showDirectoryContentsUI();
					iThread = null;
				}
			
				// Si recibimos un "1" el flujo secundario ha acabado pero el directorio no ha cambiado...
				// y además hemos de visualizar un error de "directorio vacío"...
			
				else if (message.arg1 == 1)
				{
					Log.d(TAG, "Folder is empty: " + iLoadingPathname);
					Toast.makeText(getBaseContext(), R.string.folder_is_empty, Toast.LENGTH_LONG).show();
					iThread = null;
				}

				// Si recibimos un "2" el flujo secundario ha acabado pero el directorio no ha cambiado...
				// y además hemos de visualizar un error de "directorio no leíble"...

				else if (message.arg1 == 2)
				{
					Log.d(TAG, "Folder isn't readable: " + iLoadingPathname);
					Toast.makeText(getBaseContext(), R.string.folder_isnt_readable, Toast.LENGTH_LONG).show();
					iThread = null;
				}
			}
		}
	}
	
	// Contendrá el directorio actual.
	// La variable es "protected" para permitir que los descendientes puedan acceder...
	
	protected String iCurrentPath;

	// Variable que indica si hemos inicializado o no la lista de archivos.
	
	private boolean iInitialized;
	
	// Apuntadores a los elementos que más vamos a utilizar, por comodidad.
	
	private TextView iFolderNameText;
	private ListView iListView;
	
	// Modificadores
	
	protected boolean iAllowMenuKey;
	protected boolean iShowPlainFiles;
	
	// Esta variable contendrá la lista de ficheros del directorio actual.
	
	private ArrayList<HashMap<String, Object>> iFilesList;
	
	// El objeto ListView implementa una lista que es capaz de hacer scroll 
	// (vertical), pero la gestión de la lista la hace la clase ArrayAdapter<T>
	// y SimpleAdapter es una simplificación de la misma.
	
	private SimpleAdapter iAdapterList;
	
	// Instancia de las clases que nos permitirá ordenar los ficheros.
	
	private FileNamesComparatorFoldersUp iComparatorFoldersUp;
	private FileNamesComparatorFoldersNotUp iComparatorFoldersNotUp;
	private boolean iFoldersUp;
		
	// Esta variable contendrá un apuntador al flujo secundario.
	// La usaremos para comprobar si el flujo secundario ya se está ejecutando
	// (para no duplicar)...
	
	private Thread iThread;
	
	// Instancia del Handler
	
	private Handler iHandler;
	
	// Variables internas asociadas a la comunicación entre hilos...
	
	private String iLoadingPathname;
	private List<File> iChilds;

	// Si recordais, el evento onCreate es el primero que se ejecuta al 
	// crear una actividad.
	// En este evento inicializaremos todas las variables locales de
	// la actividad.
	
	public void onCreate(Bundle saved_instance_state) 
	{
		// Llamada a la superclase (obligatorio que sea la primera instrucción del evento).
		
		super.onCreate(saved_instance_state);
		
		// Indicamos el layout de la ventana.
		
		setContentView(R.layout.main);
		
		// Inicializamos el directorio actual.

		iCurrentPath = PreferenceManager.getDefaultSharedPreferences(this).getString("root_folder", ROOT_DIRECTORY);

		// Inicialización de variables locales.
		
		iThread = null;
		
		iHandler = new MainActivityHandler();
		
		iChilds = new ArrayList<File>();
		
		iInitialized = false;
		
		iAllowMenuKey = iShowPlainFiles = true;
		
		iFolderNameText = (TextView) findViewById(R.id.folder_name);
		
		iListView = (ListView) findViewById (R.id.files_listview);
		iListView.setOnItemClickListener(this);
		
		iFilesList = new ArrayList<HashMap<String, Object>>();
		
		iComparatorFoldersUp = new FileNamesComparatorFoldersUp();
		iComparatorFoldersNotUp = new FileNamesComparatorFoldersNotUp();

		// Fijáos que indicamos un layout para cada uno de los elementos de la lista.
		
		iAdapterList = new SimpleAdapter(this, iFilesList, R.layout.file_row, new String [] { ITEM_KEY, ITEM_IMAGE }, new int [] { R.id.name, R.id.icon });
		
		// Hemos acabado. Incluímos un mensaje en el log de Android
		Log.i(TAG, "Main class created");
	}
	
	// Evento que se ejecuta cuando se destruye la actividad
	
	public void onDestroy()
	{
		// Como siempre, llamamos a la superclase. En este caso no es necesario
		// que sea la primera sentencia del procedimiento.
		super.onDestroy();
		
		// Mensaje al log.
		
		Log.d(TAG, "Main class destroyed");
	}
	
	// Evento que se ejecuta cuando se activa la actividad,
	// bien porque se acaba de crear o bien porque la actividad que
	// estaba en primer plano se ha destruído y ésta pasa a ser la actividad principal.
	
	public void onResume()
	{
		super.onResume();
		if (! iInitialized)
		{
			iInitialized = true;
			showDirectoryContents(iCurrentPath);
		}
	}
		
	// Evento que se ejecuta cuando se desactiva la actividad,
	// bien por haber sido destruída o bien porque la actividad ha dejado de estar
	// en primer plano.
	// Si no queréis hacer nada en un evento, no hace falta que incluyáis ningún 
	// código. 
	// En este caso, que sólo llamamos a la superclase, no sería necesario.

	public void onPause()
	{
		super.onPause();
	}
	
	// Función que retorna el directorio padre de uno dado.
	
	private String getParent(String pathname)
	{
		int index = pathname.lastIndexOf("/");
		if (index <= 0) return "";
		return pathname.substring(0, index);
	}
		
	// Creamos un elemento de los que necesita el adaptador.
	
	private HashMap<String, Object> createListViewItem(String name, int image)
	{
		HashMap<String, Object> item = new HashMap<String, Object>();
		item.put(ITEM_KEY, name);
		item.put(ITEM_IMAGE, image);
		return item;
	}
	
	private void showDirectoryContentsUI()
	{
		// Fijamos el directorio actual.
		
		iCurrentPath = iLoadingPathname;		
		
		// Visualizamos el nombre del directorio actual.
		
		iFolderNameText.setText(iCurrentPath + "/");
		
		// Eliminamos la lista de ficheros antiguos.
		
		iFilesList.clear();
		 
		// Si no estamos en el directorio raíz, añadimos como primer elemento
		// ir al directorio anterior.
		
		if (! iCurrentPath.equals("")) 
		{
			iFilesList.add(createListViewItem(getResources().getString(R.string.folder_up), R.drawable.folder_up));
		} 
		
		// Inicializamos la lista de ficheros.
		
		for (File child : iChilds) iFilesList.add(createListViewItem(child.getName(), child.isDirectory() ? R.drawable.folder : R.drawable.file));
	
		// Visualizamos la lista.
				
		iAdapterList.notifyDataSetChanged();
		iListView.setAdapter(iAdapterList);
	}
		
	private int _showDirectoryContents()
	{
		// Obtenemos los ficheros del directorio recibido como parámetro.
		
		File folder = new File(iLoadingPathname + "/");
		File [] childs = folder.listFiles();
		 
		if ((childs == null) || (childs.length == 0))
		{
			// Mostraremos un mensaje dependiendo de si el directorio está vacío
			// o de si no disponemos de permisos para leer su contenido.
			 
			if (folder.canRead())
			{
				// No podemos visualizar el mensaje de error aquí.
				// Sólo en el flujo principal...
				return 1;
			}
			else
			{
				// No podemos visualizar el mensaje de error aquí...
				// Sólo en el flujo principal...
				return 2;
			}
						
		}
		else
		{
			// Mostramos el contenido.
			// Mostraremos los directorios siempre y los ficheros sólo si "iShowPlainFiles" es true
			// (es decir, si no estamos en la pantalla de selección del directorio raíz)...
			
			for(File child : childs) 
			{
				if ((iShowPlainFiles) || (child.isDirectory())) iChilds.add(child);
			}

			// Ordenamos la lista de ficheros.
			// teniendo en cuenta cómo quiere verlos el usuario (si primero los directorios o no)...
			
			if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("list_folders_first", true))
			{
				Collections.sort(iChilds, iComparatorFoldersUp);
				iFoldersUp = true;
			}
			else
			{
				Collections.sort(iChilds, iComparatorFoldersNotUp);
				iFoldersUp = false;
			}
			
			// Devolvemos true para indicar que el directorio debe visualizarse...
			
			return 0;
		}
	}
	
	// Esta función enmascara a la función del mismo nombre del tutorial anterior
    // que ahora hemos renombrado como "_showDirectoryContents", y que es exactamente
	// igual que la anterior, con la salvedad de que la visualización se realiza en el flujo
	// principal y el procesado en el flujo secundario, lo que permite que nuestra aplicación
	// no se bloquee...
	// Fijáos que indicamos en la cabecera del procedimiento el modificador "synchronized"
	// para indicar que no se puede ejecutar esta función más de una vez de forma simultánea...
	
	private synchronized void showDirectoryContents(String pathname)
	{
		// Si no estamos ejecutando el flujo secundario, lo ejecutamos...
		
		if (iThread == null)
		{
			iLoadingPathname = pathname;
			iChilds.clear();
			iThread = new GetFilesThread();
			iThread.start();
		}
	}
	
	// Retorna cierto sólo si el fichero recibido es un directorio.
	
	public boolean isFolder(String pathname)
	{
		File file = new File(pathname);
		return file.isDirectory();
	}
	
	// Evento que se activa cuando se hace clic en un elemento de la lista.
	// Si el elemento es un directorio, lo mostramos, y si no lo es mostramos un mensaje
	
	public void onItemClick(AdapterView<?> arg0, View arg1, int position, long arg3) 
	{
		String filename;
		
		// Mensaje al log.
		
		Log.i(TAG, String.format("Pulsado elemento en la posición %d", position));
		
		// El primer elemento de la lista es ir al directorio anterior, 
		// pero sólo podemos ir si no estamos en el directorio raíz.
		
		if (position == 0)
		{
			if (iCurrentPath.equals(""))
			{
				filename = String.format("%s/%s", iCurrentPath, ((HashMap<String, Object>) iFilesList.get(position)).get(ITEM_KEY).toString());
			}
			else
			{
				filename = getParent(iCurrentPath);
			}
		}
		else 
		{
			filename = String.format("%s/%s", iCurrentPath, ((HashMap<String, Object>) iFilesList.get(position)).get(ITEM_KEY).toString());
			if (! isFolder(filename))
			{
				Toast.makeText(this, R.string.is_not_a_folder, Toast.LENGTH_LONG).show();
				return;
			}
		}

		// Mostramos el nuevo directorio actual.
		
		showDirectoryContents(filename);
	}
	
	// Capturaremos el evento "tecla pulsada" para no finalizar
	// la aplicación cuando se pulse la tecla "Back" sino que se 
	// vuelva al directorio anterior, y salir sólo si estamos en 
	// el directorio raíz (/).
	// Si la tecla pulsada es la de menú, iniciaremos la ventana de
	// configuración...
	
	public boolean onKeyDown(int key_code, KeyEvent event) 
	{
	    if ((key_code == KeyEvent.KEYCODE_BACK) && (! iCurrentPath.equals("")))
	    {
			Log.i(TAG, "Se ha pulsado la tecla <back> pero no estamos en el directorio raíz");
	    	showDirectoryContents(getParent(iCurrentPath));
	    	
	    	// Devolvemos cierto, para indicar que esta pulsación ya la hemos tenido en cuenta
	    	// y que el sistema no la procese.
	    	
	    	return true;
	    }
	    else if ((key_code == KeyEvent.KEYCODE_MENU) && (iAllowMenuKey))
	    {
	    	Intent intent = new Intent (this, PreferencesActivity.class);
	    	startActivityForResult(intent, 1);
	    	return true;
	    }
	    else return super.onKeyDown(key_code, event);
	} 
	
	// Evento que se activa cuando la ventana de configuración finaliza...
	// Simplemente recargaremos el directorio actual si el modo de ordenación 
	// ha cambiado...
	
	public void onActivityResult(int request_code, int result_code, Intent intent)
	{ 
		if (request_code == 1)
		{
			SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
			
			// Obtenemos el valor fijado por el usuario
			// Fijáos que pasamos un valor "por defecto" a la función "getBoolean", que es el 
			// que nos devolverá en caso que el usuario aún no haya fijado el valor...
			
			boolean value = preferences.getBoolean("list_folders_first", true);
			
			// Si el modo escogido por el usuario es diferente del que habíamos usado para generar la vista,
			// la regeneramos...
			
			if (value != iFoldersUp)
			{
				showDirectoryContents(iCurrentPath);
			}
		}
	}
}